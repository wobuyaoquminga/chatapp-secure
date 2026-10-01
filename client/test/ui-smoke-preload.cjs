const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('chat',{
  command:(action,payload)=>ipcRenderer.invoke('qa:command',{action,payload}),
  subscribe:callback=>{ipcRenderer.on('qa:snapshot',(_event,value)=>callback(value));},
  onCall:()=>{},
  onLocationStop:()=>{},
  openMap:()=>Promise.resolve()
});
