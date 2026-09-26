const {app,BrowserWindow,ipcMain,safeStorage,session}=require('electron');
const fs=require('node:fs');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const {Controller}=require('./controller.cjs');
const profileArg=process.argv.find(a=>a.startsWith('--profile='));
const profile=profileArg?profileArg.slice(10):'default';
if(!/^[a-zA-Z0-9_-]{1,40}$/.test(profile))throw new Error('Invalid profile');
// The product used to be called ChatApp. Move an existing profile to the new location on first
// launch: vaults and journals hold no absolute paths, so relocating the directory keeps every
// private key and cached history readable. Nothing is deleted — if the move is not possible the
// old directory keeps being used and the migration is retried on the next launch.
function userDataRoot(appData) {
  const oldRoot=path.join(appData,'ChatAppSecure',profile),newRoot=path.join(appData,'Chat',profile);
  if(fs.existsSync(newRoot)||!fs.existsSync(oldRoot))return newRoot;
  try { fs.mkdirSync(path.dirname(newRoot),{recursive:true});fs.renameSync(oldRoot,newRoot);return newRoot; }
  catch {
    const staging=newRoot+'.migrating';
    try {
      // Copy into a staging directory first, so an interrupted copy can never be mistaken for a
      // finished migration and silently hide the user's keys.
      fs.rmSync(staging,{recursive:true,force:true});
      fs.cpSync(oldRoot,staging,{recursive:true});
      fs.renameSync(staging,newRoot);
      return newRoot;
    }
    catch { fs.rmSync(staging,{recursive:true,force:true});return oldRoot; }
  }
}
const testAppData=profile.startsWith('qa-migrate-')&&process.argv.includes('--test-hidden')
  ?process.env.CHAT_TEST_APPDATA:null;
app.setPath('userData',userDataRoot(testAppData||app.getPath('appData')));
if(!app.requestSingleInstanceLock()){app.quit();}else{
  let window,controller;
  app.on('second-instance',()=>{window?.show();window?.focus();});
  app.whenReady().then(()=>{
    const entry=path.join(__dirname,'ui','index.html');
    // Chromium upper-cases the drive letter of a file: URL while Node keeps the case it was
    // launched with, so both sides are folded before comparing, exactly as Windows paths are.
    const entryUrl=pathToFileURL(entry).href.toLowerCase();
    controller=new Controller(app.getPath('userData'),safeStorage,event=>{if(window&&!window.isDestroyed())window.webContents.send('chat:event',event);});
    window=new BrowserWindow({width:1040,height:900,minWidth:560,minHeight:650,show:false,title:'Chat',
      icon:path.join(__dirname,'assets','icon.ico'),
      webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,devTools:!app.isPackaged}});
    window.removeMenu();window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.webContents.on('will-navigate',event=>event.preventDefault());
    session.defaultSession.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false));
    ipcMain.handle('chat:command',(event,{action,payload})=>{
      if(event.sender!==window.webContents||event.senderFrame.url.toLowerCase()!==entryUrl)throw new Error('IPC sender rejected');
      if(!['login','send','history','safety','logout','forgetAccount','forgetServer','saveServer','selectServer','addContact','acceptContact','removeContact','clearConversation','openConversation','refreshContacts','snapshot'].includes(action))throw new Error('Unsupported command');
      return controller.serial(async()=>{
        try{return {ok:true,value:await controller[action](payload)}}catch(e){return {ok:false,error:e.message}}
      });
    });
    window.loadFile(entry);window.once('ready-to-show',()=>{if(!process.argv.includes('--test-hidden'))window.show();});
  });
  app.on('window-all-closed',()=>app.quit());
  app.on('before-quit',()=>controller?.logout());
}
