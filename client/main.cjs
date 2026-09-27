const {app,BrowserWindow,ipcMain,safeStorage,session}=require('electron');
const fs=require('node:fs');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const {Controller}=require('./controller.cjs');
const {NativeLocation}=require('./native-location.cjs');
const nativeLocation=new NativeLocation();
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
  let window,controller,geoUntil=0,geoPeer='',geoGeneration=-1;
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
    const geoAllowed=(contents,permission,details)=>['geolocation','geolocation-approximate'].includes(permission)&&contents===window.webContents&&Date.now()<geoUntil&&window.isFocused()&&details?.isMainFrame===true&&details.requestingUrl?.toLowerCase()===entryUrl;
    session.defaultSession.setPermissionRequestHandler((contents,permission,callback,details)=>callback(geoAllowed(contents,permission,details)));
    session.defaultSession.setPermissionCheckHandler((contents,permission,_origin,details)=>geoAllowed(contents,permission,details));
    window.on('blur',()=>{geoUntil=0;nativeLocation.cancel();window.webContents.send('chat:location-stop');controller.serial(()=>controller.stopLocations());});
    let closing=false;window.on('close',event=>{geoUntil=0;nativeLocation.cancel();if(closing)return;event.preventDefault();closing=true;window.webContents.send('chat:location-stop');Promise.race([controller.serial(()=>controller.stopLocations()),new Promise(resolve=>setTimeout(resolve,2000))]).finally(()=>window.destroy());});
    ipcMain.handle('chat:command',async(event,{action,payload})=>{
      if(event.sender!==window.webContents||event.senderFrame.url.toLowerCase()!==entryUrl)throw new Error('IPC sender rejected');
      if(action==='sendLocation'&&!window.isFocused()&&require('./ui/features.js').parse(payload?.body)?.kind!=='stop')return {ok:false,error:'请在应用前台发送位置'};
      if(action==='nativePosition'){
        const generation=controller.generation,peer=payload?.peer;
        const authorized=()=>Date.now()<geoUntil&&peer===geoPeer&&generation===geoGeneration&&controller.generation===generation&&controller.online&&window&&!window.isDestroyed()&&window.isFocused()&&controller.contactState[peer]?.status==='accepted'&&!controller.engine?.state.identityChanges?.[peer]&&!controller.engine?.state.deletedPeers?.[peer];
        try{return {ok:true,value:await nativeLocation.query(authorized)};}catch(e){return {ok:false,error:e.message};}
      }
      if(action==='locationPermission'){
        if(!controller.online||!window.isFocused()||controller.contactState[payload?.peer]?.status!=='accepted')return {ok:false,error:'请在在线且已接受的聊天中操作'};
        geoPeer=payload.peer;geoGeneration=controller.generation;geoUntil=Date.now()+(payload?.live?3600000:30000);return {ok:true,value:true};
      }
      if(action==='revokeLocationPermission'){geoUntil=0;nativeLocation.cancel();return {ok:true,value:true};}
      if(!['sendLocation','stopLocations','login','send','history','safety','logout','forgetAccount','forgetServer','saveServer','selectServer','addContact','acceptContact','removeContact','clearConversation','openConversation','refreshContacts','snapshot'].includes(action))throw new Error('Unsupported command');
      return controller.serial(async()=>{
        try{if(['logout','login','saveServer','selectServer','forgetServer'].includes(action)){geoUntil=0;nativeLocation.cancel();await controller.stopLocations();}return {ok:true,value:await controller[action](payload)}}catch(e){return {ok:false,error:e.message}}
      });
    });
    window.loadFile(entry);window.once('ready-to-show',()=>{if(!process.argv.includes('--test-hidden'))window.show();});
  });
  app.on('window-all-closed',()=>app.quit());
  app.on('before-quit',()=>{nativeLocation.cancel();controller?.logout();});
}
