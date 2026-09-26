const path=require('node:path');
const WebSocket=require('ws');
const {SignalEngine}=require('./signal.cjs');
const {Vault,AccountRegistry}=require('./vault.cjs');
const validUser=user=>typeof user==='string'&&/^[a-z0-9_\p{Script=Han}]{2,32}$/u.test(user)&&(/\p{Script=Han}/u.test(user)||user.length>=3);

class Controller {
  constructor(directory,safeStorage,notify) {
    this.vaults=path.join(directory,'vaults');this.safeStorage=safeStorage;this.notify=notify;
    this.registry=new AccountRegistry(directory,safeStorage);this.queue=Promise.resolve();this.generation=0;
    this.status='未登录';this.online=false;this.contactState={};
  }
  serial(task) { const run=this.queue.then(task);this.queue=run.catch(()=>{});return run; }
  // A corrupt index must never keep the user away from keys that are still intact in its vault.
  accountList() {
    try { this.listError='';return this.registry.list(); }
    catch(e) { this.listError=e.message;return []; }
  }
  snapshot() {
    const accounts=this.accountList();let servers=[],selectedServer='';
    try{servers=this.registry.servers();selectedServer=this.registry.selectedServer();}
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
      messages:this.engine ? Object.values(this.engine.state.messages).map(({ciphertext,...m})=>m) : []};
  }
  update(error) { this.notify({...this.snapshot(),error:error||this.listError||''}); }
  async transaction(task) {
    if(this.storageFailed)throw new Error('本地保存失败后已暂停加解密，请退出并重新登录');
    const previous=structuredClone(this.engine.state);
    try {
      const result=await task();
      try{this.vault.write(this.engine.state);}catch(e){this.storageFailed=true;throw e;}
      return result;
    }
    catch(e) { this.engine.state=previous;throw e; }
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
    if(!validUser(username)) throw new Error('用户名限 2–32 位中文、小写字母、数字、下划线；纯英文等至少 3 位');
    this.server=url.origin;
    const auth=await this.request('/api/auth/'+(register?'register':'login'),'POST',{username,password});
    this.token=auth.token;this.user=username;
    try {
      this.vault=new Vault(this.vaults,this.server,username,this.safeStorage);
      const saved=this.vault.read();this.engine=saved ? new SignalEngine(saved) : await SignalEngine.create(username);
      const own=await this.request('/api/keys/me'),publicInfo=await this.engine.publicBundle();
      if(own.identityKey && own.identityKey!==publicInfo.identityKey) throw new Error('账号已绑定另一份本地密钥。本版单设备，无法仅用密码恢复，请使用原客户端');
      this.vault.write(this.engine.state);
      // Keys for this account are now confirmed to live on this device, so it is safe to remember it.
      try{this.registry.remember(this.server,username);}catch(e){this.listError='账号列表保存失败：'+e.message;}
      await this.replenish(own);
      await this.syncAccountEvents();
      await this.refreshContacts();
      this.connect();return this.snapshot();
    } catch(e) {this.logout();throw e;}
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
  connect() {
    const epoch=++this.generation;
    this.status='连接中';this.online=false;this.update();
    const socket=new WebSocket(this.server.replace(/^http/,'ws')+'/ws',{maxPayload:131072,handshakeTimeout:10000});this.socket=socket;
    socket.on('open',()=>{if(epoch===this.generation)socket.send(JSON.stringify({type:'auth',token:this.token}));});
    socket.on('message',data=>this.serial(async()=>{
      if(epoch!==this.generation)return;
      let event;
      try { event=JSON.parse(data.toString());await this.event(event); }
      catch(e){
        if(['ready','account_deleted'].includes(event?.type)){
          this.online=false;this.status='账号状态同步失败，正在重连';socket.close();
        }
        this.update('消息未确认：'+e.message);
      }
    }));
    socket.on('close',(code)=>this.serial(async()=>{
      if(epoch!==this.generation)return;
      this.online=false;
      if(code===1008){this.status='认证已失效，请退出后重新登录';this.update();return;}
      this.status='离线，正在重连';this.update();
      this.retry=setTimeout(()=>{if(epoch===this.generation)this.connect();},2000);
    }));
    socket.on('error',()=>{});
  }
  wire(value) {if(this.socket?.readyState===WebSocket.OPEN)this.socket.send(JSON.stringify(value));}
  async bindPeerIdentity(peer,info) {
    const retired=this.engine.state.deletedPeers?.[peer];
    if(retired) {
      if(!info.accountId||info.accountId===retired.accountId)throw new Error('该用户已销户，请等待对方重新注册后重新发起聊天');
      delete this.engine.state.deletedPeers[peer];
    }
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
        (state.deletedPeers ||= {})[peer]={accountId:event.accountId,identityKey:event.identityKey,deletedAt:event.deletedAt||''};
        if(state.hiddenSessions)delete state.hiddenSessions[peer];
        if(state.peerAccountIds)delete state.peerAccountIds[peer];
        retired=true;
      }
      handled[event.id]=true;
    });
    if(retired)delete this.contactState[event.username];
  }
  async syncAccountEvents() {
    let events;
    try{events=await this.request('/api/account-events');}catch(error){if(error.status===404)return;throw error;}
    if(!Array.isArray(events))throw new Error('账号清理通知列表格式无效');
    for(const event of events)await this.applyAccountDeletion(event);
    for(let start=0;start<events.length;start+=1000)
      await this.request('/api/account-events/ack','POST',{ids:events.slice(start,start+1000).map(event=>event.id)});
  }
  async event(event) {
    if(event.type==='ready') {
      this.online=false;this.status='同步账号状态';
      await this.syncAccountEvents();
      await this.refreshContacts();
      this.online=true;this.status='在线 · 端到端加密';
      for(const item of Object.values(this.engine.state.outbox))this.wire(item);
    } else if(event.type==='account_deleted') {
      await this.applyAccountDeletion(event.event);
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
  async send({peer,body}) {
    if(!this.engine||!this.online)throw new Error('请等待连接恢复');
    if(!validUser(peer)||peer===this.user)throw new Error('请输入另一位有效用户');
    if(typeof body!=='string'||!body.trim()||body.length>4000)throw new Error('消息须为 1–4000 字符');
    const status=this.contactState[peer]?.status;
    if(status==='pending_incoming')throw new Error('请先接受对方的聊天请求');
    if(status==='pending_outgoing')
      throw new Error('请等待对方接受聊天请求，之前只能发送一条消息');
    const identity=await this.request('/api/keys/'+encodeURIComponent(peer));
    if(this.engine.state.deletedPeers?.[peer])throw new Error('该用户已销户，请在“发起聊天”中重新查找对方');
    await this.transaction(()=>this.bindPeerIdentity(peer,identity));
    if(!await this.engine.hasSession(peer)) {
      const bundle=await this.request('/api/keys/'+encodeURIComponent(peer)+'/claim','POST');
      await this.transaction(()=>this.engine.establish(peer,bundle));
    }
    const envelope=await this.transaction(()=>this.engine.encrypt(peer,body));
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
        this.engine.state.verified[peer]=info.identityKey;result.verified=true;
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
    ++this.generation;clearTimeout(this.retry);this.socket?.close();this.socket=null;
    this.user=null;this.server=null;this.token=null;this.engine=null;this.vault=null;this.online=false;this.status='未登录';this.contactState={};this.update();
  }
}
module.exports={Controller,validUser};
