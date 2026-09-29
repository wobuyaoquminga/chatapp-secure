const path=require('node:path');
const crypto=require('node:crypto');
const WebSocket=require('ws');
const {SignalEngine}=require('./signal.cjs');
const {Vault,AccountRegistry}=require('./vault.cjs');
const Location=require('./ui/features.js');
const validUser=user=>typeof user==='string'&&/^[a-z0-9_\p{Script=Han}]{2,32}$/u.test(user);
const DEFAULT_CONNECTION_OPTIONS={readyTimeoutMs:30000,heartbeatIntervalMs:30000,pongTimeoutMs:10000,retryBaseMs:1000,retryMaxMs:30000,random:Math.random};

class Controller {
  constructor(directory,safeStorage,notify,connectionOptions={}) {
    this.vaults=path.join(directory,'vaults');this.safeStorage=safeStorage;this.notify=notify;
    this.registry=new AccountRegistry(directory,safeStorage);this.queue=Promise.resolve();this.generation=0;
    this.connectionOptions={...DEFAULT_CONNECTION_OPTIONS,...connectionOptions};this.retryAttempt=0;
    this.status='未登录';this.online=false;this.contactState={};this.liveSessions=new Map();
    this.call=null;
  }
  serial(task) { const run=this.queue.then(task);this.queue=run.catch(()=>{});return run; }
  snapshot() {
    let accounts=[],servers=[],selectedServer='';
    // Read/decrypt once per update so all three fields describe one committed index.
    // Do not cache across updates: corruption and changes on disk must remain visible.
    try{
      const registry=this.registry.read()||{};this.listError='';
      accounts=registry.accounts||[];
      servers=registry.servers||[...new Set(accounts.map(account=>account.server))];
      selectedServer=registry.selectedServer||servers[0]||'';
    }
    catch(e){this.listError=e.message;}
    const contacts=Object.values(this.contactState).filter(item=>item.status==='accepted'&&!this.engine?.state.deletedPeers?.[item.username]&&!this.engine?.state.hiddenContacts?.[item.username]).map(item=>item.username);
    const sessions=new Set();
    if(this.engine)for(const message of Object.values(this.engine.state.messages)) {
      const peer=message.sender===this.user?message.recipient:message.sender;
      if(peer&&peer!==this.user&&!this.engine.state.hiddenSessions?.[peer])sessions.add(peer);
    }
    for(const item of Object.values(this.contactState))if(item.status==='pending_incoming'&&!this.engine?.state.hiddenSessions?.[item.username])sessions.add(item.username);
    for(const peer of Object.keys(this.engine?.state.deletedPeers||{}))if(!this.engine.state.hiddenSessions?.[peer])sessions.add(peer);
    return {username:this.user||'',server:this.server||'',status:this.status,online:this.online,
      accounts,servers,selectedServer,contacts:contacts.sort(),sessions:[...sessions].sort(),
      contactStates:Object.values(this.contactState),
      verifiedPeers:this.engine?Object.keys(this.engine.state.verified):[],
      deletedPeers:this.engine?.state.deletedPeers||{},
      identityChanges:this.engine?.state.identityChanges||{},
      messages:this.engine ? Object.values(this.engine.state.messages).map(({ciphertext,...m})=>m) : []};
  }
  update(error) {
    if(this.call){try{if(this.call.accountId!==this.callPeer(this.call.peer))this.endCall('联系人身份已变化');}catch{this.endCall('联系人状态已变化');}}
    this.notify({...this.snapshot(),error:error||this.listError||''});
  }
  async transaction(task) {
    if(this.storageFailed)throw new Error('本地保存失败后已暂停加解密，请退出并重新登录');
    const previous=structuredClone(this.engine.state);
    try {
      const result=await task();
      try{this.vault.write(this.engine.state);}catch(e){this.storageFailed=true;throw e;}
      return result;
    }
    catch(e) {
      this.engine.state=previous;
      if(this.storageFailed){
        this.online=false;this.status='本地保存失败，已暂停连接，请退出并重新登录';
        this.socket?.terminate();this.update();
      }
      throw e;
    }
  }
  async request(endpoint,method='GET',body) {
    const response=await fetch(this.server+endpoint,{method,redirect:'error',signal:AbortSignal.timeout(15000),
      headers:{'Content-Type':'application/json',...(this.token?{Authorization:'Bearer '+this.token}:{})},body:body===undefined?undefined:JSON.stringify(body)});
    const data=await response.json().catch(()=>({}));
    if(!response.ok){const error=new Error(data.error||'服务器请求失败 ('+response.status+')');error.status=response.status;throw error;}return data;
  }
  async login({server,username,password,register}) {
    this.logout();
    this.storageFailed=false;
    const url=new URL(server);
    if (!['http:','https:'].includes(url.protocol)||url.username||url.password||url.pathname!=='/'||url.search||url.hash) throw new Error('请输入服务器根地址，例如 https://chat.example.com');
    if(url.protocol==='http:'&&!['localhost','127.0.0.1','[::1]'].includes(url.hostname)) throw new Error('远程服务器必须使用 HTTPS，HTTP 仅允许本机测试');
    if(!validUser(username)) throw new Error('用户名限 2–32 位中文、小写字母、数字、下划线');
    this.server=url.origin;
    const auth=await this.request('/api/auth/'+(register?'register':'login'),'POST',{username,password});
    this.token=auth.token;this.user=username;this.accountId=auth.accountId;
    try {
      this.vault=new Vault(this.vaults,this.server,username,this.safeStorage);
      const saved=this.vault.read();this.engine=saved ? new SignalEngine(saved) : await SignalEngine.create(username);
      let own=await this.request('/api/keys/me');
      await this.recoverIdentity(own,password);
      own=await this.request('/api/keys/me');
      this.vault.write(this.engine.state);
      // Keys for this account are now confirmed to live on this device, so it is safe to remember it.
      try{this.registry.remember(this.server,username);}catch(e){this.listError='账号列表保存失败：'+e.message;}
      await this.replenish(own);
      await this.syncAccountEvents();
      await this.refreshContacts();
      this.connect();return this.snapshot();
    } catch(e) {this.logout();throw e;}
  }
  async promoteIdentity(candidate) {
    const old=this.engine.state;
    for(const name of ['messages','hiddenSessions','hiddenContacts','trusted','peerAccountIds','deletedPeers','appliedAccountEvents'])
      candidate.state[name]=structuredClone(old[name]||{});
    for(const message of Object.values(candidate.state.messages)) {
      if(message.id){message.archivedServerId=message.id;delete message.id;}
      if(old.outbox[message.clientId])message.status='设备身份已更新，旧消息未发送';
    }
    candidate.state.identityChanges={};
    for(const peer of Object.keys(candidate.state.peerAccountIds))candidate.state.identityChanges[peer]={identityKey:candidate.state.trusted[peer+'.1'],accountId:candidate.state.peerAccountIds[peer],message:'本机身份已更新，请重新核对安全码'};
    this.vault.write(candidate.state);this.engine=candidate;
  }
  async recoverIdentity(own,password) {
    const state=this.engine.state;
    let candidate=state.pendingIdentityReset?new SignalEngine(structuredClone(state.pendingIdentityReset)):null;
    if(candidate&&(await candidate.publicBundle()).identityKey===own.identityKey) {
      await this.promoteIdentity(candidate);return;
    }
    if(!own.identityKey || own.identityKey===(await this.engine.publicBundle()).identityKey)return;
    if(!candidate) {
      candidate=await SignalEngine.create(this.user);await candidate.publicBundle(30);
      state.pendingIdentityReset=structuredClone(candidate.state);
      this.vault.write(state); // Persist candidate keys before remote commit; history is kept once.
    }
    const auth=await this.request('/api/keys/reset','POST',{password,bundle:await candidate.publicBundle()});
    this.token=auth.token;this.accountId=auth.accountId;candidate.state.accountId=auth.accountId;
    await this.promoteIdentity(candidate);
  }
  normalizeServer(server) {
    const url=new URL(server);
    if (!['http:','https:'].includes(url.protocol)||url.username||url.password||url.pathname!=='/'||url.search||url.hash)throw new Error('请输入服务器根地址，例如 https://chat.example.com');
    if(url.protocol==='http:'&&!['localhost','127.0.0.1','[::1]'].includes(url.hostname))throw new Error('远程服务器必须使用 HTTPS，HTTP 仅允许本机测试');
    return url.origin;
  }
  saveServer({server}) {
    const normalized=this.normalizeServer(server);
    this.registry.saveServer(normalized);this.logout();return this.snapshot();
  }
  selectServer({server}) {
    this.registry.selectServer(this.normalizeServer(server));this.logout();return this.snapshot();
  }
  forgetServer({server}) {
    this.registry.forgetServer(this.normalizeServer(server));this.logout();return this.snapshot();
  }
  async addContact({peer}) {
    if(!this.engine||!validUser(peer)||peer===this.user)throw new Error('请输入另一位有效用户');
    const info=await this.request('/api/keys/'+encodeURIComponent(peer));
    await this.transaction(async()=>{
      await this.bindPeerIdentity(peer,info);
      if(this.engine.state.hiddenContacts)delete this.engine.state.hiddenContacts[peer];
      if(this.engine.state.hiddenSessions)delete this.engine.state.hiddenSessions[peer];
    });
    this.update();return this.snapshot();
  }
  async refreshContacts() {
    const items=await this.request('/api/contacts');
    if(!Array.isArray(items))throw new Error('联系人列表格式不正确');
    for(const item of items)if(validUser(item.username)&&this.engine?.state.deletedPeers?.[item.username]) {
      const info=await this.request('/api/keys/'+encodeURIComponent(item.username));
      if(info.accountId&&info.accountId!==this.engine.state.deletedPeers[item.username].accountId)
        await this.transaction(()=>this.bindPeerIdentity(item.username,info));
    }
    this.contactState=Object.fromEntries(items.filter(item=>validUser(item.username)&&item.username!==this.user&&!this.engine?.state.deletedPeers?.[item.username]).map(item=>[item.username,item]));
    this.update();return this.snapshot();
  }
  async acceptContact({peer}) {
    if(!this.engine||!validUser(peer)||this.contactState[peer]?.status!=='pending_incoming')throw new Error('没有待接受的聊天请求');
    await this.request('/api/contacts/accept','POST',{peer});
    await this.transaction(()=>{if(this.engine.state.hiddenContacts)delete this.engine.state.hiddenContacts[peer];});
    await this.refreshContacts();return this.snapshot();
  }
  async replenish(own) {
    own ||= await this.request('/api/keys/me');
    // Persist private halves before publishing. Reuse this exact batch after interruption.
    const bundle=await this.transaction(()=>this.engine.publicBundle(this.engine.state.pendingUpload.length?0:(own.remaining<10?30:0)));
    await this.request('/api/keys','PUT',bundle);
    await this.transaction(()=>{this.engine.state.pendingUpload=[];});
  }
  clearConnectionTimers() {
    clearTimeout(this.retry);clearTimeout(this.readyTimeout);clearTimeout(this.pongTimeout);clearInterval(this.heartbeat);
    this.retry=this.readyTimeout=this.pongTimeout=this.heartbeat=null;
    this.pingChallenge=null;
  }
  retryDelay() {
    const {retryBaseMs,retryMaxMs,random}=this.connectionOptions;
    const ceiling=Math.min(retryMaxMs,retryBaseMs*2**Math.min(this.retryAttempt++,30));
    return Math.floor(ceiling*(0.5+Math.min(1,Math.max(0,random()))/2));
  }
  connect() {
    if(this.storageFailed||this.authFailed||!this.token||!this.server)return;
    this.clearConnectionTimers();
    const epoch=++this.generation;
    this.status='连接中';this.online=false;this.update();
    const socket=new WebSocket(this.server.replace(/^http/,'ws')+'/ws',{maxPayload:131072,handshakeTimeout:10000});this.socket=socket;
    socket.on('open',()=>{
      if(epoch!==this.generation)return;
      this.readyTimeout=setTimeout(()=>{if(epoch===this.generation&&socket.readyState===WebSocket.OPEN)socket.terminate();},this.connectionOptions.readyTimeoutMs);
      this.heartbeat=setInterval(()=>{
        if(epoch!==this.generation||socket.readyState!==WebSocket.OPEN)return;
        if(this.pongTimeout){socket.terminate();return;}
        const challenge=crypto.randomBytes(8);this.pingChallenge=challenge;
        this.pongTimeout=setTimeout(()=>{if(epoch===this.generation&&socket.readyState===WebSocket.OPEN)socket.terminate();},this.connectionOptions.pongTimeoutMs);
        try{socket.ping(challenge);}catch{socket.terminate();}
      },this.connectionOptions.heartbeatIntervalMs);
      try{socket.send(JSON.stringify({type:'auth',token:this.token}));}catch{socket.terminate();}
    });
    socket.on('pong',data=>{
      if(epoch===this.generation&&this.pingChallenge?.equals(data)){
        clearTimeout(this.pongTimeout);this.pongTimeout=null;this.pingChallenge=null;
      }
    });
    socket.on('message',data=>{
      let event,parseError;
      try{event=JSON.parse(data.toString());}
      catch(e){parseError=e;}
      // The ready frame has arrived even if an earlier message is still being processed.
      if(event?.type==='ready'&&epoch===this.generation){clearTimeout(this.readyTimeout);this.readyTimeout=null;}
      this.serial(async()=>{
        if(epoch!==this.generation)return;
        try {
          if(parseError)throw parseError;
          await this.event(event);
          if(event?.type==='ready'&&socket.readyState===WebSocket.OPEN&&epoch===this.generation)this.retryAttempt=0;
        }
        catch(e){
          if(this.storageFailed){this.online=false;this.status='本地保存失败，已暂停连接，请退出并重新登录';socket.terminate();}
          if(event?.type==='ready'&&(e.status===401||e.status===403)){
            this.authFailed=true;this.online=false;this.status='认证已失效，请退出后重新登录';socket.terminate();
          }
          if(['ready','account_deleted','identity_reset'].includes(event?.type)){
            if(!this.storageFailed&&!this.authFailed){this.online=false;this.status='账号状态同步失败，正在重连';socket.close();}
          }
          this.update('消息未确认：'+e.message);
        }
      });
    });
    socket.on('close',(code)=>this.serial(async()=>{
      if(epoch!==this.generation)return;
      this.clearConnectionTimers();this.socket=null;
      this.online=false;this.liveSessions.clear();this.endCall('连接已断开');
      if(this.storageFailed){this.status='本地保存失败，已暂停连接，请退出并重新登录';this.update();return;}
      if(code===1008||this.authFailed){this.status='认证已失效，请退出后重新登录';this.update();return;}
      this.status='离线，正在重连';this.update();
      this.retry=setTimeout(()=>{if(epoch===this.generation&&!this.storageFailed&&!this.authFailed)this.connect();},this.retryDelay());
    }));
    socket.on('error',()=>{});
  }
  wire(value) {if(this.socket?.readyState===WebSocket.OPEN)this.socket.send(JSON.stringify(value));}
  callPeer(peer) {
    if(!this.online||this.socket?.readyState!==WebSocket.OPEN)throw new Error('通话需要在线连接');
    if(!validUser(peer)||peer===this.user||this.contactState[peer]?.status!=='accepted'||this.engine?.state.deletedPeers?.[peer]||this.engine?.state.identityChanges?.[peer])throw new Error('只能与已接受且身份未变化的联系人通话');
    const accountId=this.engine?.state.peerAccountIds?.[peer]||this.contactState[peer]?.accountId;
    if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(accountId||''))throw new Error('联系人账号信息未就绪，请刷新联系人');
    return accountId;
  }
  endCall(reason) {if(this.call){const {callId}=this.call;this.call=null;this.notify({type:'call_end',callId,reason});}}
  cancelCall({callId}) {if(this.call?.callId===callId)this.endCall('通话已结束');return true;}
  async callIce() {
    if(!this.online)throw new Error('离线时不能通话');
    const data=await this.request('/api/calls/ice');
    if(!Array.isArray(data.iceServers))throw new Error('ICE 配置格式无效');
    return data.iceServers;
  }
  beginCall({peer,callId,mode}) {
    const accountId=this.callPeer(peer);
    if(this.call||!['audio','video'].includes(mode)||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(callId||''))throw new Error('当前无法开始通话');
    this.call={peer,callId,mode,accountId,generation:this.generation,userApproved:true};return true;
  }
  approveCall({callId}) {if(!this.call||this.call.callId!==callId||this.call.userApproved||this.call.generation!==this.generation)throw new Error('来电已失效');this.callPeer(this.call.peer);this.call.userApproved=true;return true;}
  sendCall({peer,callId,action,mode,payload}) {
    const toAccountId=this.callPeer(peer);
    if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(callId||'')||!['offer','answer','ice','reject','busy','hangup'].includes(action)||!['audio','video'].includes(mode)||(['offer','answer','ice'].includes(action)?typeof payload!=='string'||!payload.trim()||payload.length>(action==='ice'?4096:65536):payload!==undefined))throw new Error('通话信令无效');
    if(!this.call||this.call.peer!==peer||this.call.callId!==callId||this.call.mode!==mode||this.call.accountId!==toAccountId||this.call.generation!==this.generation)throw new Error('通话已结束或联系人身份已变化');
    if(['offer','answer','ice'].includes(action)&&!this.call.userApproved)throw new Error('尚未接听来电');
    this.wire({type:'call',to:peer,toAccountId,callId,action,mode,...(payload===undefined?{}:{payload})});
    if(['hangup','reject','busy'].includes(action))this.endCall('通话已结束');
    return true;
  }
  receiveCall(event) {
    if(!this.online||!validUser(event.from)||!['offer','answer','ice','reject','busy','hangup'].includes(event.action)||!['audio','video'].includes(event.mode)||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(event.callId||'')||(['offer','answer','ice'].includes(event.action)?typeof event.payload!=='string'||!event.payload.trim()||event.payload.length>(event.action==='ice'?4096:65536):event.payload!==undefined))return;
    let accountId;try{accountId=this.callPeer(event.from);}catch{return;}
    if(event.fromAccountId!==accountId)return;
    if(event.action==='offer'){
      if(this.call){
        if(this.call.peer===event.from&&this.call.callId===event.callId&&this.call.mode===event.mode&&this.call.accountId===accountId)return;
        this.wire({type:'call',to:event.from,toAccountId:accountId,callId:event.callId,action:'busy',mode:event.mode});return;
      }
      this.call={peer:event.from,callId:event.callId,mode:event.mode,accountId,generation:this.generation,userApproved:false};
    }else if(!this.call||this.call.peer!==event.from||this.call.callId!==event.callId||this.call.mode!==event.mode||this.call.accountId!==accountId)return;
    this.notify({type:'call',event:{type:'call',from:event.from,fromAccountId:accountId,callId:event.callId,action:event.action,mode:event.mode,...(typeof event.payload==='string'?{payload:event.payload}:{})}});
    if(['reject','busy','hangup'].includes(event.action))this.endCall('对方已结束通话');
  }
  async bindPeerIdentity(peer,info) {
    const retired=this.engine.state.deletedPeers?.[peer];
    if(retired) {
      if(!info.accountId||info.accountId===retired.accountId)throw new Error('该用户已销户，请等待对方重新注册后重新发起聊天');
      delete this.engine.state.deletedPeers[peer];
    }
    const known=this.engine.state.peerAccountIds?.[peer];
    if(!retired&&known&&info.accountId&&known!==info.accountId)throw new Error('对方设备身份已更新，请等待服务器身份通知后重试');
    const result=await this.engine.safety(peer,info.identityKey);
    if(info.accountId)(this.engine.state.peerAccountIds ||= {})[peer]=info.accountId;
    return result;
  }
  async applyAccountDeletion(event) {
    if(!event||typeof event.id!=='string'||!validUser(event.username)||event.username===this.user||typeof event.accountId!=='string'||typeof event.identityKey!=='string')throw new Error('账号清理通知格式无效');
    let retired=false;
    await this.transaction(async()=>{
      const state=this.engine.state,handled=(state.appliedAccountEvents ||= {});
      if(handled[event.id])return;
      const peer=event.username,known=state.peerAccountIds?.[peer],trusted=state.trusted?.[peer+'.1'];
      const newer=(known&&known!==event.accountId)||(trusted&&event.identityKey&&trusted!==event.identityKey);
      if(!newer) {
        await this.engine.retirePeer(peer);
        if(state.identityChanges)delete state.identityChanges[peer];
        (state.deletedPeers ||= {})[peer]={accountId:event.accountId,identityKey:event.identityKey,deletedAt:event.deletedAt||''};
        if(state.hiddenSessions)delete state.hiddenSessions[peer];
        if(state.peerAccountIds)delete state.peerAccountIds[peer];
        retired=true;
      }
      handled[event.id]=true;
    });
    if(retired)delete this.contactState[event.username];
  }
  async applyAccountEvent(event) {
    if(event?.kind!=='identity_reset')return this.applyAccountDeletion(event);
    if(typeof event.id!=='string'||!validUser(event.username)||event.username===this.user||typeof event.accountId!=='string'||typeof event.identityKey!=='string'||!event.newAccountId||!event.newIdentityKey)throw new Error('身份更新通知格式无效');
    await this.transaction(async()=>{
      const state=this.engine.state,handled=(state.appliedAccountEvents ||= {}),peer=event.username;
      if(handled[event.id])return;
      const known=state.peerAccountIds?.[peer],trusted=state.trusted?.[peer+'.1'];
      if((!known||known===event.accountId)&&(!trusted||trusted===event.identityKey)) {
        await this.engine.retirePeer(peer,'对方设备身份已更新，旧消息未发送');
        await this.engine.safety(peer,event.newIdentityKey);
        (state.peerAccountIds ||= {})[peer]=event.newAccountId;
        (state.identityChanges ||= {})[peer]={identityKey:event.newIdentityKey,accountId:event.newAccountId,message:'对方设备身份已更新，请重新核对安全码'};
        if(state.deletedPeers)delete state.deletedPeers[peer];
      }
      handled[event.id]=true;
    });
  }
  async syncAccountEvents() {
    let events;
    try{events=await this.request('/api/account-events');}catch(error){if(error.status===404)return;throw error;}
    if(!Array.isArray(events))throw new Error('账号清理通知列表格式无效');
    for(const event of events)await this.applyAccountEvent(event);
    for(let start=0;start<events.length;start+=1000)
      await this.request('/api/account-events/ack','POST',{ids:events.slice(start,start+1000).map(event=>event.id)});
  }
  async event(event) {
    if(event.type==='call'){this.receiveCall(event);return;}
    if(event.type==='call_error'){if(this.call&&event.callId===this.call.callId){this.notify({type:'call_error',event});this.endCall(event.error||'通话信令失败');}return;}
    if(event.type==='ready') {
      this.online=false;this.status='同步账号状态';
      await this.syncAccountEvents();
      await this.refreshContacts();
      this.online=true;this.status='在线 · 消息端到端加密';
      for(const item of Object.values(this.engine.state.outbox))this.wire(item);
    } else if(['account_deleted','identity_reset'].includes(event.type)) {
      await this.applyAccountEvent({...event.event,kind:event.type});
      await this.request('/api/account-events/ack','POST',{ids:[event.event.id]});
    } else if(event.type==='message') {
      if(!this.online)return;
      await this.transaction(async()=>{
        await this.engine.decrypt(event.message);
        if(this.engine.state.hiddenSessions)delete this.engine.state.hiddenSessions[event.message.sender];
      });
      this.wire({type:'ack',id:event.message.id});
    } else if(event.type==='accepted') {
      await this.transaction(()=>this.engine.accepted(event.message));
    } else if(event.type==='delivered') {
      await this.transaction(()=>{
        for(const message of Object.values(this.engine.state.messages))
          if(message.sender===this.user&&message.id===event.id)message.status='对方客户端已接收';
      });
    } else if(event.type==='contact' && validUser(event.contact?.username)) {
      if(event.contact.status==='removed')delete this.contactState[event.contact.username];
      else {
        if(this.engine.state.deletedPeers?.[event.contact.username])await this.transaction(async()=>{
          const info=await this.request('/api/keys/'+encodeURIComponent(event.contact.username));
          await this.bindPeerIdentity(event.contact.username,info);
        });
        this.contactState[event.contact.username]=event.contact;
        if(['pending_incoming','accepted'].includes(event.contact.status)&&this.engine.state.hiddenContacts?.[event.contact.username])
          await this.transaction(()=>{delete this.engine.state.hiddenContacts[event.contact.username];});
      }
    } else if(event.type==='presence' && validUser(event.username)) {
      if(this.contactState[event.username])this.contactState[event.username].online=!!event.online;
    } else if(event.type==='error') {
      // Keep the same encrypted outbox entry: never re-encrypt a retry.
      this.update(event.error+'；未确认的密文仍在本地待发队列');return;
    }
    this.update();
  }
  async sendLocation({peer,body}) {
    const location=Location.parse(body);if(!location)throw new Error('位置数据无效');
    if(this.contactState[peer]?.status!=='accepted')throw new Error('位置功能需要双方接受聊天');
    if(!this.online||!this.token||!this.engine||this.socket?.readyState!==WebSocket.OPEN)throw new Error('离线时不能共享位置');
    if(Date.parse(location.expiresAt)<=Date.now()&&location.kind!=='stop')throw new Error('位置已过期');
    const key=peer+'\0'+location.sessionId,previous=this.liveSessions.get(key);
    if(location.kind==='live'){
      if(previous&&(previous.stopped||location.seq<=previous.seq||Date.now()>=previous.expiresAt))throw new Error('共享已停止或更新过期');
      if(previous&&Date.now()-previous.sentAt<10000)throw new Error('实时位置每十秒最多更新一次');
      if(previous&&Date.parse(location.expiresAt)>previous.expiresAt)throw new Error('不能延长共享时间');
    }
    const result=await this.send({peer,body,ephemeral:location.kind!=='pin',location:true});
    if(location.kind!=='pin')this.liveSessions.set(key,{peer,sessionId:location.sessionId,seq:location.seq,sentAt:Date.now(),expiresAt:previous?.expiresAt||Date.parse(location.expiresAt),stopped:location.kind==='stop'});
    return result;
  }
  async stopLocations() {
    for(const item of this.liveSessions.values())if(!item.stopped&&item.expiresAt>Date.now()){
      try{await this.sendLocation({peer:item.peer,body:Location.encode({v:1,kind:'stop',sessionId:item.sessionId,seq:item.seq+1,recordedAt:new Date().toISOString(),expiresAt:new Date(Math.max(Date.now(),item.expiresAt)).toISOString()})});}catch{}
    }
    this.liveSessions.clear();
  }
  async send({peer,body,ephemeral=false,location=false}) {
    if(!location&&typeof body==='string'&&body.startsWith(Location.PREFIX))return this.sendLocation({peer,body});
    const generation=this.generation;
    if(!this.engine||!this.online)throw new Error('请等待连接恢复');
    if(!validUser(peer)||peer===this.user)throw new Error('请输入另一位有效用户');
    if(typeof body!=='string'||!body.trim()||body.length>4000)throw new Error('消息须为 1–4000 字符');
    if(this.engine.state.identityChanges?.[peer])throw new Error('设备身份已更新，请先核对新的安全码');
    const status=this.contactState[peer]?.status;
    if(status==='pending_incoming')throw new Error('请先接受对方的聊天请求');
    if(status==='pending_outgoing')
      throw new Error('请等待对方接受聊天请求，之前只能发送一条消息');
    const identity=await this.request('/api/keys/'+encodeURIComponent(peer));
    if(this.engine.state.deletedPeers?.[peer])throw new Error('该用户已销户，请在“发起聊天”中重新查找对方');
    await this.transaction(()=>this.bindPeerIdentity(peer,identity));
    if(!await this.engine.hasSession(peer)) {
      const bundle=await this.request('/api/keys/'+encodeURIComponent(peer)+'/claim','POST');
      await this.transaction(async()=>{await this.bindPeerIdentity(peer,bundle);await this.engine.establish(peer,bundle);});
    }
    const envelope=await this.transaction(async()=>{
      if(ephemeral&&(!this.online||generation!==this.generation||this.socket?.readyState!==WebSocket.OPEN))throw new Error('连接已断开，实时位置没有排队');
      const value=await this.engine.encrypt(peer,body);
      if(this.engine.state.peerAccountIds?.[peer])value.toAccountId=this.engine.state.peerAccountIds[peer];
      // Persist the ratchet and encrypted local history, but never a retry queue for live updates.
      if(ephemeral)delete this.engine.state.outbox[value.clientId];
      return value;
    });
    if(!status)this.contactState[peer]={username:peer,status:'pending_outgoing',online:false};
    if(this.engine.state.hiddenSessions?.[peer])await this.transaction(()=>{delete this.engine.state.hiddenSessions[peer];});
    this.wire(envelope);this.update();return this.snapshot();
  }
  clearConversation({peer}) {
    if(!this.engine||!validUser(peer)||peer===this.user)throw new Error('先选择一个会话');
    return this.transaction(()=>{(this.engine.state.hiddenSessions ||= {})[peer]=true;}).then(()=>{this.update();return this.snapshot();});
  }
  openConversation({peer}) {
    if(!this.engine||!validUser(peer)||peer===this.user)throw new Error('先选择一位用户');
    return this.transaction(()=>{if(this.engine.state.hiddenSessions)delete this.engine.state.hiddenSessions[peer];}).then(()=>{this.update();return this.snapshot();});
  }
  async removeContact({peer}) {
    if(!this.engine||!validUser(peer)||this.contactState[peer]?.status!=='accepted')throw new Error('先选择一位联系人');
    await this.request('/api/contacts/remove','POST',{peer});
    if(this.contactState[peer]?.status==='accepted')delete this.contactState[peer];
    await this.transaction(()=>{if(this.engine.state.hiddenContacts)delete this.engine.state.hiddenContacts[peer];});
    this.update();return this.snapshot();
  }
  async history({peer}) {
    if(!this.engine)throw new Error('请登录');
    const records=await this.request('/api/messages?peer='+encodeURIComponent(peer));
    for(const record of records.reverse()) {
      // ACKed older messages already exist in the DPAPI-protected cache.
      // Pending messages are delivered in order through WS, so history never races the ratchet.
      if(record.sender===this.user && this.engine.state.messages[this.user+':'+record.clientId])
        await this.transaction(()=>this.engine.accepted(record));
    }
    this.update();return this.snapshot();
  }
  async safety({peer,confirm,expectedCode}) {
    if(!this.engine||!validUser(peer)||peer===this.user)throw new Error('先选择另一位用户');
    const info=await this.request('/api/keys/'+encodeURIComponent(peer));
    const result=await this.transaction(async()=>{
      const result=await this.bindPeerIdentity(peer,info);
      if(confirm) {
        if(expectedCode!==result.code)throw new Error('安全码已改变，请重新核对');
        this.engine.state.verified[peer]=info.identityKey;if(this.engine.state.identityChanges)delete this.engine.state.identityChanges[peer];result.verified=true;
      }
      return result;
    });
    this.update();return result;
  }
  forgetAccount({server,user}) {
    // Drops the saved entry only. The vault holding this account's keys stays on disk:
    // deleting it would permanently lose the history that only those keys can decrypt.
    this.registry.forget(server,user);
    this.update();return this.snapshot();
  }
  logout() {
    this.endCall('账号已退出');this.liveSessions.clear();++this.generation;this.clearConnectionTimers();this.socket?.close();this.socket=null;this.retryAttempt=0;this.authFailed=false;
    this.user=null;this.accountId=null;this.server=null;this.token=null;this.engine=null;this.vault=null;this.online=false;this.status='未登录';this.contactState={};this.update();
  }
}
module.exports={Controller,validUser};
