const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('chat',{
  command:(action,payload)=>ipcRenderer.invoke('chat:command',{action,payload}),
  subscribe:callback=>{const listener=(_event,data)=>callback(data);ipcRenderer.on('chat:event',listener);return ()=>ipcRenderer.removeListener('chat:event',listener);}
});
