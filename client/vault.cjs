const fs=require('node:fs');
const path=require('node:path');
const {createHash}=require('node:crypto');
// Append-only encrypted checkpoint journal. A crash can only leave a trailing partial
// record, which is discarded on read, so the last complete record always wins.
class Journal {
  constructor(file,crypto,corruptMessage) {
    this.crypto=crypto;this.file=file;this.corruptMessage=corruptMessage;
    if (!crypto.isEncryptionAvailable()) throw new Error('Windows 安全存储不可用，拒绝将私钥明文保存');
    fs.mkdirSync(path.dirname(file),{recursive:true});
  }
  encode(state){return JSON.stringify(state);}
  decode(value){return JSON.parse(value);}
  read() {
    if (!fs.existsSync(this.file)) return null;
    try {
      const journal=fs.readFileSync(this.file),end=journal.lastIndexOf(10);
      if(end<0)throw new Error('Incomplete record');
      const start=journal.lastIndexOf(10,end-1)+1;
      const encrypted=Buffer.from(journal.subarray(start,end).toString('ascii'),'base64');
      const state=this.decode(this.crypto.decryptString(encrypted));
      // A crash before fsync can leave an uncommitted trailing record; it was never sent/ACKed.
      if(end!==journal.length-1)fs.truncateSync(this.file,end+1);
      return state;
    }
    catch { throw new Error(this.corruptMessage); }
  }
  write(state) {
    if(!this.crypto.isEncryptionAvailable())throw new Error('Windows 安全存储不可用，拒绝明文保存');
    const data=this.crypto.encryptString(this.encode(state));
    // Append a complete encrypted checkpoint, then flush before advancing the protocol.
    // No cross-volume rename dependency, including redirected Windows profile folders.
    const record=data.toString('base64')+'\n';
    const previousEnd=fs.existsSync(this.file)?fs.statSync(this.file).size:0;
    try{fs.appendFileSync(this.file,record,{flush:true});}
    catch(error){try{fs.truncateSync(this.file,previousEnd);}catch{}throw error;}
    // Append is already committed; optional compaction failure cannot invalidate it.
    const temp=this.file+'.compact';
    try {if(fs.statSync(this.file).size>Math.max(8*1024*1024,Buffer.byteLength(record)*3)){
      fs.writeFileSync(temp,record,{flag:'w',flush:true});fs.renameSync(temp,this.file);
    }}catch {try{fs.unlinkSync(temp);}catch{}}

  }
}
class Vault extends Journal {
  constructor(directory,server,user,safeStorage) {
    super(path.join(directory,createHash('sha256').update(server+'\0'+user).digest('hex')+'.vault'),
      safeStorage,'本地安全存储损坏或属于另一 Windows 用户；不会自动覆盖旧密钥');
    this.historyFile=this.file+'.history';
    this.historyEnd=0;
    this.historyCommitted=false;
  }
  // Legacy JSON-only readers fail closed on this DPAPI-encrypted version marker.
  encode(state){return state.historyFormat===2?'CHAT_VAULT_V2:'+JSON.stringify(state):super.encode(state);}
  decode(value){return super.decode(value.startsWith('CHAT_VAULT_V2:')?value.slice('CHAT_VAULT_V2:'.length):value);}
  read() {
    const state=super.read();
    if(!state||state.historyFormat!==2)return state;
    try {
      const data=fs.readFileSync(this.historyFile);
      if(!Number.isSafeInteger(state.historyEnd)||state.historyEnd<0||state.historyEnd>data.length)throw Error('history offset');
      const messages={};let start=0;
      while(start<state.historyEnd){
        const end=data.indexOf(10,start);
        if(end<0||end>=state.historyEnd)throw Error('history record');
        const change=JSON.parse(this.crypto.decryptString(Buffer.from(data.subarray(start,end).toString('ascii'),'base64')));
        if(change.replace)Object.keys(messages).forEach(key=>delete messages[key]);
        for(const [key,value] of Object.entries(change.messages||{})){
          if(value===null)delete messages[key];else messages[key]=value;
        }
        start=end+1;
      }
      this.historyEnd=state.historyEnd;
      this.historyCommitted=true;
      const {historyFormat,historyEnd,...protocol}=state;
      return {...protocol,messages};
    }catch{throw new Error(this.corruptMessage);}
  }
  write(state,changes) {
    if(!Object.hasOwn(state,'messages'))return super.write(state);
    if(!this.crypto.isEncryptionAvailable())throw new Error('Windows 安全存储不可用，拒绝明文保存');
    const migrated=!this.historyCommitted;
    const replace=migrated||changes===undefined;
    const messages=replace?state.messages:changes;
    let end=this.historyEnd;
    if(replace||Object.keys(messages).length){
      const record=this.crypto.encryptString(JSON.stringify({replace,messages})).toString('base64')+'\n';
      // Only the protocol checkpoint commits these bytes. A failed checkpoint leaves
      // an uncommitted suffix, which is ignored and overwritten on the next write.
      if(fs.existsSync(this.historyFile))fs.truncateSync(this.historyFile,this.historyEnd);
      fs.appendFileSync(this.historyFile,record,{flush:true});
      end+=Buffer.byteLength(record);
    }
    const {messages:discarded,...protocol}=state;
    super.write({...protocol,historyFormat:2,historyEnd:end});
    this.historyEnd=end;
    this.historyCommitted=true;
  }
}
// Index of the accounts signed in on this machine, so the sign-in screen can pre-fill the
// server address and username. Passwords and private keys are never stored here.
class AccountRegistry extends Journal {
  constructor(directory,safeStorage) {
    super(path.join(directory,'accounts.journal'),safeStorage,'本地账号列表损坏，已停止读取；不会自动覆盖');
  }
  list() { return this.read()?.accounts||[]; }
  servers() {
    const state=this.read();
    return state?.servers||[...new Set((state?.accounts||[]).map(a=>a.server))];
  }
  selectedServer() {return this.read()?.selectedServer||this.servers()[0]||'';}
  saveServer(server) {
    const accounts=this.list(),servers=[server,...this.servers().filter(s=>s!==server)];
    this.write({version:2,accounts,servers,selectedServer:server});return servers;
  }
  selectServer(server) {
    if(!this.servers().includes(server))throw new Error('服务器不在已保存列表');
    return this.saveServer(server);
  }
  remember(server,user,accountId) {
    const currentAccounts=this.list(),previous=currentAccounts.find(a=>a.server===server&&a.user===user);
    const accounts=currentAccounts.filter(a=>a.server!==server||a.user!==user);
    const account={server,user};
    if(accountId){account.accountId=accountId;if(previous?.accountId===accountId&&previous.accountStatus?.accountId===accountId)account.accountStatus=previous.accountStatus;}
    else if(previous)Object.assign(account,previous);
    accounts.unshift(account);this.write({version:2,accounts,servers:[server,...this.servers().filter(s=>s!==server)],selectedServer:server});return accounts;
  }
  recordStatus(server,user,accountId,status) {
    const current=this.read()||{},accounts=current.accounts||[];
    const index=accounts.findIndex(a=>a.server===server&&a.user===user&&a.accountId===accountId);
    if(index<0)return false;
    const updated=accounts.map((item,at)=>at===index?{...item,accountStatus:{...status,accountId}}:item);
    this.write({...current,accounts:updated});return true;
  }
  forget(server,user) {
    const accounts=this.list().filter(a=>a.server!==server||a.user!==user);
    this.write({version:2,accounts,servers:this.servers(),selectedServer:this.selectedServer()});return accounts;
  }
  forgetServer(server) {
    const servers=this.servers().filter(s=>s!==server),accounts=this.list().filter(a=>a.server!==server);
    const selectedServer=this.selectedServer()===server?(servers[0]||''):this.selectedServer();
    this.write({version:2,accounts,servers,selectedServer});return servers;
  }
}
module.exports={Vault,AccountRegistry};
