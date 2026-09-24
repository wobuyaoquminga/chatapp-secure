const fs=require('node:fs');
const path=require('node:path');
const {createHash}=require('node:crypto');
class Vault {
  constructor(directory,server,user,safeStorage) {
    this.crypto=safeStorage;
    if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows 安全存储不可用，拒绝将私钥明文保存');
    fs.mkdirSync(directory,{recursive:true});
    this.file=path.join(directory,createHash('sha256').update(server+'\0'+user).digest('hex')+'.vault');
  }
  read() {
    if (!fs.existsSync(this.file)) return null;
    try {
      const journal=fs.readFileSync(this.file),end=journal.lastIndexOf(10);
      if(end<0)throw new Error('Incomplete vault');
      const start=journal.lastIndexOf(10,end-1)+1;
      const encrypted=Buffer.from(journal.subarray(start,end).toString('ascii'),'base64');
      const state=JSON.parse(this.crypto.decryptString(encrypted));
      // A crash before fsync can leave an uncommitted trailing record; it was never sent/ACKed.
      if(end!==journal.length-1)fs.truncateSync(this.file,end+1);
      return state;
    }
    catch { throw new Error('本地安全存储损坏或属于另一 Windows 用户；不会自动覆盖旧密钥'); }
  }
  write(state) {
    const data=this.crypto.encryptString(JSON.stringify(state));
    // Append a complete encrypted checkpoint, then flush before advancing the protocol.
    // No cross-volume rename dependency, including redirected Windows profile folders.
    fs.appendFileSync(this.file,data.toString('base64')+'\n',{flush:true});
  }
}
module.exports={Vault};
