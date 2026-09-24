const WebSocket=require('ws');
const {SignalEngine}=require('./signal.cjs');
const {Vault}=require('./vault.cjs');

class Controller {
  constructor(directory,safeStorage,notify) {
    this.directory=directory;this.safeStorage=safeStorage;this.notify=notify;this.queue=Promise.resolve();this.generation=0;
    this.status='未登录';this.online=false;
  }
  serial(task) { const run=this.queue.then(task);this.queue=run.catch(()=>{});return run; }
  snapshot() {
    return {username:this.user||'',server:this.server||'',status:this.status,online:this.online,
      verifiedPeers:this.engine?Object.keys(this.engine.state.verified):[],
      messages:this.engine ? Object.values(this.engine.state.messages).map(({ciphertext,...m})=>m) : []};
  }
  update(error) { this.notify({...this.snapshot(),error}); }
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
    if(!response.ok) throw new Error(data.error||'服务器请求失败 ('+response.status+')');return data;
  }
  async login({server,username,password,register}) {
    this.logout();
    this.storageFailed=false;
    const url=new URL(server);
    if (!['http:','https:'].includes(url.protocol)||url.username||url.password||url.pathname!=='/'||url.search||url.hash) throw new Error('请输入服务器根地址，例如 https://chat.example.com');
    if(url.protocol==='http:'&&!['localhost','127.0.0.1','[::1]'].includes(url.hostname)) throw new Error('远程服务器必须使用 HTTPS，HTTP 仅允许本机测试');
    if(!/^[a-z0-9_]{3,32}$/.test(username)) throw new Error('用户名限 3–32 位小写字母、数字、下划线');
    this.server=url.origin;
    const auth=await this.request('/api/auth/'+(register?'register':'login'),'POST',{username,password});
    this.token=auth.token;this.user=username;
    try {
      this.vault=new Vault(this.directory,this.server,username,this.safeStorage);
      const saved=this.vault.read();this.engine=saved ? new SignalEngine(saved) : await SignalEngine.create(username);
      const own=await this.request('/api/keys/me'),publicInfo=await this.engine.publicBundle();
      if(own.identityKey && own.identityKey!==publicInfo.identityKey) throw new Error('账号已绑定另一份本地密钥。本版单设备，无法仅用密码恢复，请使用原客户端');
      this.vault.write(this.engine.state);
      await this.replenish(own);
      this.connect();return this.snapshot();
    } catch(e) {this.logout();throw e;}
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
      try { await this.event(JSON.parse(data.toString())); }
      catch(e){this.update('消息未确认：'+e.message);}
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
  async event(event) {
    if(event.type==='ready') {
      this.online=true;this.status='在线 · 端到端加密';
      for(const item of Object.values(this.engine.state.outbox))this.wire(item);
    } else if(event.type==='message') {
      await this.transaction(()=>this.engine.decrypt(event.message));
      this.wire({type:'ack',id:event.message.id});
    } else if(event.type==='accepted') {
      await this.transaction(()=>this.engine.accepted(event.message));
    } else if(event.type==='delivered') {
      await this.transaction(()=>{
        for(const message of Object.values(this.engine.state.messages))
          if(message.sender===this.user&&message.id===event.id)message.status='对方客户端已接收';
      });
    } else if(event.type==='error') {
      // Keep the same encrypted outbox entry: never re-encrypt a retry.
      this.update(event.error+'；未确认的密文仍在本地待发队列');return;
    }
    this.update();
  }
  async send({peer,body}) {
    if(!this.engine||!this.online)throw new Error('请等待连接恢复');
    if(!/^[a-z0-9_]{3,32}$/.test(peer)||peer===this.user)throw new Error('请输入另一位有效用户');
    if(typeof body!=='string'||!body.trim()||body.length>4000)throw new Error('消息须为 1–4000 字符');
    const identity=await this.request('/api/keys/'+encodeURIComponent(peer));
    await this.transaction(()=>this.engine.safety(peer,identity.identityKey));
    if(!await this.engine.hasSession(peer)) {
      const bundle=await this.request('/api/keys/'+encodeURIComponent(peer)+'/claim','POST');
      await this.transaction(()=>this.engine.establish(peer,bundle));
    }
    const envelope=await this.transaction(()=>this.engine.encrypt(peer,body));
    this.wire(envelope);this.update();return this.snapshot();
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
    if(!this.engine||!/^[a-z0-9_]{3,32}$/.test(peer)||peer===this.user)throw new Error('先选择另一位用户');
    const info=await this.request('/api/keys/'+encodeURIComponent(peer));
    const result=await this.transaction(async()=>{
      const result=await this.engine.safety(peer,info.identityKey);
      if(confirm) {
        if(expectedCode!==result.code)throw new Error('安全码已改变，请重新核对');
        this.engine.state.verified[peer]=info.identityKey;result.verified=true;
      }
      return result;
    });
    this.update();return result;
  }
  logout() {
    ++this.generation;clearTimeout(this.retry);this.socket?.close();this.socket=null;
    this.user=null;this.token=null;this.engine=null;this.vault=null;this.online=false;this.status='未登录';this.update();
  }
}
module.exports={Controller};
