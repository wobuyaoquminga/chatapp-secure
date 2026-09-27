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
  read() {
    if (!fs.existsSync(this.file)) return null;
    try {
      const journal=fs.readFileSync(this.file),end=journal.lastIndexOf(10);
      if(end<0)throw new Error('Incomplete record');
      const start=journal.lastIndexOf(10,end-1)+1;
      const encrypted=Buffer.from(journal.subarray(start,end).toString('ascii'),'base64');
      const state=JSON.parse(this.crypto.decryptString(encrypted));
      // A crash before fsync can leave an uncommitted trailing record; it was never sent/ACKed.
      if(end!==journal.length-1)fs.truncateSync(this.file,end+1);
      return state;
    }
    catch { throw new Error(this.corruptMessage); }
  }
  write(state) {
    if(!this.crypto.isEncryptionAvailable())throw new Error('Windows 安全存储不可用，拒绝明文保存');
    const data=this.crypto.encryptString(JSON.stringify(state));
    // Append a complete encrypted checkpoint, then flush before advancing the protocol.
    // No cross-volume rename dependency, including redirected Windows profile folders.
    const record=data.toString('base64')+'\n';
    fs.appendFileSync(this.file,record,{flush:true});
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
  remember(server,user) {
    const accounts=this.list().filter(a=>a.server!==server||a.user!==user);
    accounts.unshift({server,user});this.write({version:2,accounts,servers:[server,...this.servers().filter(s=>s!==server)],selectedServer:server});return accounts;
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
