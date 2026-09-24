const {app,BrowserWindow,ipcMain,safeStorage,session}=require('electron');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const {Controller}=require('./controller.cjs');
const profileArg=process.argv.find(a=>a.startsWith('--profile='));
const profile=profileArg?profileArg.slice(10):'default';
if(!/^[a-zA-Z0-9_-]{1,40}$/.test(profile))throw new Error('Invalid profile');
app.setPath('userData',path.join(app.getPath('appData'),'ChatAppSecure',profile));
if(!app.requestSingleInstanceLock()){app.quit();}else{
  let window,controller;
  app.on('second-instance',()=>{window?.show();window?.focus();});
  app.whenReady().then(()=>{
    const entry=path.join(__dirname,'ui','index.html');
    controller=new Controller(path.join(app.getPath('userData'),'vaults'),safeStorage,event=>{if(window&&!window.isDestroyed())window.webContents.send('chat:event',event);});
    window=new BrowserWindow({width:1040,height:900,minWidth:560,minHeight:650,show:false,title:'ChatApp · 加密通讯',
      webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,devTools:!app.isPackaged}});
    window.removeMenu();window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.webContents.on('will-navigate',event=>event.preventDefault());
    session.defaultSession.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false));
    ipcMain.handle('chat:command',(event,{action,payload})=>{
      if(event.sender!==window.webContents||event.senderFrame.url!==pathToFileURL(entry).href)throw new Error('IPC sender rejected');
      if(!['login','send','history','safety','logout','snapshot'].includes(action))throw new Error('Unsupported command');
      return controller.serial(async()=>{
        try{return {ok:true,value:await controller[action](payload)}}catch(e){return {ok:false,error:e.message}}
      });
    });
    window.loadFile(entry);window.once('ready-to-show',()=>{if(!process.argv.includes('--test-hidden'))window.show();});
  });
  app.on('window-all-closed',()=>app.quit());
  app.on('before-quit',()=>controller?.logout());
}
