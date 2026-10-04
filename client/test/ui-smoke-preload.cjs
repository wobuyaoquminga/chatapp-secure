const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('chat',{
  command:(action,payload)=>ipcRenderer.invoke('qa:command',{action,payload}),
  subscribe:callback=>{ipcRenderer.on('qa:snapshot',(_event,value)=>callback(value));},
  onCall:()=>{},
  onLocationStop:()=>{},
  openMap:()=>Promise.resolve(),
  openUpdates:()=>Promise.resolve(),
  checkUpdate:()=>ipcRenderer.invoke('qa:check-update'),
  downloadUpdate:()=>ipcRenderer.invoke('qa:download-update'),
  cancelUpdate:()=>ipcRenderer.invoke('qa:cancel-update'),
  openDownload:()=>ipcRenderer.invoke('qa:open-download'),
  onUpdateProgress:callback=>ipcRenderer.on('qa:update-progress',(_event,value)=>callback(value))
});
