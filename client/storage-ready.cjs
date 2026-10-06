const fs=require('node:fs');
const path=require('node:path');
const {setTimeout:delay}=require('node:timers/promises');

// Electron's Windows OSCrypt key is saved through Chromium's delayed preferences
// writer. Do not commit a vault before that key survives a process crash.
async function waitForStorageKey(directory,{timeout=20000}={}){
  const file=path.join(directory,'Local State'),deadline=Date.now()+timeout;
  do{
    try{
      const state=JSON.parse(await fs.promises.readFile(file,'utf8'));
      const key=state.os_crypt?.encrypted_key;
      if(typeof key==='string'&&Buffer.from(key,'base64').subarray(0,5).toString()==='DPAPI'){
        const handle=await fs.promises.open(file,'r+');
        try{await handle.sync();}finally{await handle.close();}
        return;
      }
    }catch(error){
      if(error.code&&error.code!=='ENOENT')throw error;
    }
    await delay(50);
  }while(Date.now()<deadline);
  throw Error('系统加密密钥尚未保存。请重新打开 Chat；现有聊天记录不会被覆盖。');
}
module.exports={waitForStorageKey};
