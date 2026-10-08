const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('chat',{
  onCall:callback=>{const listener=(_event,data)=>callback(data);ipcRenderer.on('chat:call',listener);return ()=>ipcRenderer.removeListener('chat:call',listener);},
  onLocationStop:callback=>{const listener=()=>callback();ipcRenderer.on('chat:location-stop',listener);return ()=>ipcRenderer.removeListener('chat:location-stop',listener);},
  command:(action,payload)=>ipcRenderer.invoke('chat:command',{action,payload}),
  sendFile:(peer,kind)=>ipcRenderer.invoke('chat:send-file',peer,kind),
  saveFile:(clientId,peer)=>ipcRenderer.invoke('chat:save-file',clientId,peer),
  previewFile:(clientId,peer)=>ipcRenderer.invoke('chat:preview-file',clientId,peer),
  cancelFilePreview:()=>ipcRenderer.invoke('chat:cancel-file-preview'),
  openMap:(latitude,longitude)=>ipcRenderer.invoke('chat:open-map',latitude,longitude),
  reverseLocation:(latitude,longitude,peer)=>ipcRenderer.invoke('chat:reverse-location',latitude,longitude,peer),
  openUpdates:()=>ipcRenderer.invoke('chat:open-updates'),
  checkUpdate:()=>ipcRenderer.invoke('chat:check-update'),
  downloadUpdate:()=>ipcRenderer.invoke('chat:download-update'),
  cancelUpdate:()=>ipcRenderer.invoke('chat:cancel-update'),
  openDownload:()=>ipcRenderer.invoke('chat:open-download'),
  onUpdateProgress:callback=>{const listener=(_event,data)=>callback(data);ipcRenderer.on('chat:update-progress',listener);return ()=>ipcRenderer.removeListener('chat:update-progress',listener);},
  subscribe:callback=>{const listener=(_event,data)=>callback(data);ipcRenderer.on('chat:event',listener);return ()=>ipcRenderer.removeListener('chat:event',listener);}
});
