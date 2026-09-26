'use strict';
const { randomInt, randomUUID } = require('node:crypto');
const lib = import('@signalapp/libsignal-client');
const b64 = value => Buffer.from(value).toString('base64');
const bytes = value => Buffer.from(value, 'base64');

// All cryptographic operations are delegated to official libsignal.
// The caller serializes operations and atomically persists this entire state before network I/O.
class SignalEngine {
  constructor(state) { this.state = state; }
  static async create(username) {
    const s = await lib, identity = s.PrivateKey.generate(), signed = s.PrivateKey.generate();
    const record = s.SignedPreKeyRecord.new(1, Date.now(), signed.getPublicKey(), signed, identity.sign(signed.getPublicKey().serialize()));
    return new SignalEngine({ version:1, username, registrationId:randomInt(1,16384), identity:b64(identity.serialize()),
      signed:{1:b64(record.serialize())}, pre:{}, kyber:{}, usedKyber:{}, nextKey:1, sessions:{}, trusted:{}, verified:{},
      messages:{}, outbox:{}, pendingUpload:[] });
  }
  async stores() {
    const s = await lib, engine=this;
    const key = address => address.toString();
    const read = (map,id,type) => { const value=engine.state[map][id]; if (!value) throw new Error('本地密钥或会话缺失'); return type.deserialize(bytes(value)); };
    return {
      session: new class extends s.SessionStore {
        async saveSession(a,r) { engine.state.sessions[key(a)]=b64(r.serialize()); }
        async getSession(a) { return engine.state.sessions[key(a)] ? read('sessions',key(a),s.SessionRecord) : null; }
        async getExistingSessions(addresses) { return Promise.all(addresses.map(a=>this.getSession(a))); }
      }(),
      identity: new class extends s.IdentityKeyStore {
        async getIdentityKey() { return s.PrivateKey.deserialize(bytes(engine.state.identity)); }
        async getLocalRegistrationId() { return engine.state.registrationId; }
        async saveIdentity(a,k) {
          const old=engine.state.trusted[key(a)], next=b64(k.serialize());
          if (old && old!==next) throw new Error('对方身份密钥已改变，已阻止通信');
          engine.state.trusted[key(a)]=next; return s.IdentityChange.NewOrUnchanged;
        }
        async isTrustedIdentity(a,k) { const old=engine.state.trusted[key(a)]; return !old || old===b64(k.serialize()); }
        async getIdentity(a) { const old=engine.state.trusted[key(a)]; return old ? s.PublicKey.deserialize(bytes(old)) : null; }
      }(),
      pre: new class extends s.PreKeyStore {
        async savePreKey(id,r) { engine.state.pre[id]=b64(r.serialize()); }
        async getPreKey(id) { return read('pre',id,s.PreKeyRecord); }
        async removePreKey(id) { delete engine.state.pre[id]; }
      }(),
      signed: new class extends s.SignedPreKeyStore {
        async saveSignedPreKey(id,r) { engine.state.signed[id]=b64(r.serialize()); }
        async getSignedPreKey(id) { return read('signed',id,s.SignedPreKeyRecord); }
      }(),
      kyber: new class extends s.KyberPreKeyStore {
        async saveKyberPreKey(id,r) { engine.state.kyber[id]=b64(r.serialize()); }
        async getKyberPreKey(id) { return read('kyber',id,s.KyberPreKeyRecord); }
        async markKyberPreKeyUsed(id,signedId,baseKey) {
          const use=signedId+':'+b64(baseKey.serialize());
          if (engine.state.usedKyber[id] && engine.state.usedKyber[id]!==use) throw new Error('一次性 Kyber 预密钥被重复使用');
          engine.state.usedKyber[id]=use;
        }
      }()
    };
  }
  async publicBundle(count=0) {
    const s=await lib, identity=s.PrivateKey.deserialize(bytes(this.state.identity));
    const sp=s.SignedPreKeyRecord.deserialize(bytes(this.state.signed[1]));
    for (let i=0;i<count;i++) {
      const id=this.state.nextKey++, ec=s.PrivateKey.generate(), kem=s.KEMKeyPair.generate();
      const kp=s.KyberPreKeyRecord.new(id,Date.now(),kem,identity.sign(kem.getPublicKey().serialize()));
      this.state.pre[id]=b64(s.PreKeyRecord.new(id,ec.getPublicKey(),ec).serialize());
      this.state.kyber[id]=b64(kp.serialize());
      this.state.pendingUpload.push({id,publicKey:b64(ec.getPublicKey().serialize()),kyberPublicKey:b64(kem.getPublicKey().serialize()),kyberSignature:b64(kp.signature())});
    }
    return {identityKey:b64(identity.getPublicKey().serialize()),registrationId:this.state.registrationId,
      signedPreKey:{id:1,publicKey:b64(sp.publicKey().serialize()),signature:b64(sp.signature())},preKeys:this.state.pendingUpload};
  }
  async hasSession(peer) {
    const s=await lib, stores=await this.stores();
    const record=await stores.session.getSession(s.ProtocolAddress.new(peer,1)); return !!record?.hasCurrentState();
  }
  async establish(peer,bundle) {
    const s=await lib, stores=await this.stores(), p=bundle.preKey, sp=bundle.signedPreKey;
    if (bundle.username!==peer) throw new Error('预密钥账号不匹配');
    const key=s.PreKeyBundle.new(bundle.registrationId,1,p.id,s.PublicKey.deserialize(bytes(p.publicKey)),sp.id,
      s.PublicKey.deserialize(bytes(sp.publicKey)),bytes(sp.signature),s.PublicKey.deserialize(bytes(bundle.identityKey)),
      p.id,s.KEMPublicKey.deserialize(bytes(p.kyberPublicKey)),bytes(p.kyberSignature));
    await s.processPreKeyBundle(key,s.ProtocolAddress.new(peer,1),s.ProtocolAddress.new(this.state.username,1),stores.session,stores.identity);
  }
  async encrypt(to,body) {
    const s=await lib, stores=await this.stores(), clientId=randomUUID();
    const content={v:1,clientId,sender:this.state.username,recipient:to,body,createdAt:new Date().toISOString()};
    const message=await s.signalEncrypt(Buffer.from(JSON.stringify(content)),s.ProtocolAddress.new(to,1),s.ProtocolAddress.new(this.state.username,1),stores.session,stores.identity);
    const ciphertext=JSON.stringify({v:1,type:message.type(),data:b64(message.serialize())});
    const request={type:'send',clientId,to,ciphertext};
    this.state.outbox[clientId]=request;
    this.state.messages[this.state.username+':'+clientId]={...content,status:'待发送',ciphertext};
    return request;
  }
  async decrypt(message) {
    const cacheKey=message.sender+':'+message.clientId;
    const existing=this.state.messages[cacheKey];
    if (existing) {
      if (existing.ciphertext!==message.ciphertext || existing.recipient!==message.recipient) throw new Error('重复消息内容不一致');
      return existing;
    }
    const s=await lib, stores=await this.stores(), envelope=JSON.parse(message.ciphertext);
    if (envelope.v!==1 || ![2,3].includes(envelope.type)) throw new Error('不支持的加密消息格式');
    const remote=s.ProtocolAddress.new(message.sender,1), local=s.ProtocolAddress.new(this.state.username,1);
    const plain=envelope.type===3
      ? await s.signalDecryptPreKey(s.PreKeySignalMessage.deserialize(bytes(envelope.data)),remote,local,stores.session,stores.identity,stores.pre,stores.signed,stores.kyber)
      : await s.signalDecrypt(s.SignalMessage.deserialize(bytes(envelope.data)),remote,local,stores.session,stores.identity);
    const content=JSON.parse(Buffer.from(plain).toString('utf8'));
    if (content.v!==1 || content.sender!==message.sender || content.recipient!==this.state.username || content.clientId!==message.clientId || message.recipient!==this.state.username || typeof content.body!=='string')
      throw new Error('加密正文与路由信息不匹配');
    this.state.messages[cacheKey]={...content,id:message.id,status:'已接收并安全保存',ciphertext:message.ciphertext};
    return this.state.messages[cacheKey];
  }
  accepted(message) {
    const key=this.state.username+':'+message.clientId, local=this.state.messages[key];
    if (!local || local.ciphertext!==message.ciphertext || local.recipient!==message.recipient) throw new Error('服务器确认与本地消息不一致');
    local.id=message.id; local.status=message.acknowledged?'对方客户端已接收':'服务器已保存密文'; delete this.state.outbox[message.clientId];
  }
  async safety(peer, remotePublic) {
    const s=await lib, identity=s.PrivateKey.deserialize(bytes(this.state.identity));
    const address=s.ProtocolAddress.new(peer,1), old=this.state.trusted[address.toString()];
    if (old && old!==remotePublic) throw new Error('对方身份密钥已改变，已阻止通信');
    this.state.trusted[address.toString()]=remotePublic;
    const code=s.Fingerprint.new(5200,2,Buffer.from(this.state.username),identity.getPublicKey(),Buffer.from(peer),s.PublicKey.deserialize(bytes(remotePublic))).displayableFingerprint().toString();
    return {code,verified:this.state.verified[peer]===remotePublic,publicKey:remotePublic};
  }
  async retirePeer(peer) {
    const s=await lib,key=s.ProtocolAddress.new(peer,1).toString();
    delete this.state.sessions[key];delete this.state.trusted[key];delete this.state.verified[peer];
    for(const [id,request] of Object.entries(this.state.outbox)) {
      if(request.to!==peer)continue;
      delete this.state.outbox[id];
      const message=this.state.messages[this.state.username+':'+id];
      if(message)message.status='该用户已销户，旧消息未发送';
    }
    for(const message of Object.values(this.state.messages))if(message.sender===peer||message.recipient===peer) {
      if(message.id){message.archivedServerId=message.id;delete message.id;}
    }
  }
}
module.exports={SignalEngine};
