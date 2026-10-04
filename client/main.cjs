const {app,BrowserWindow,Notification,ipcMain,safeStorage,session,shell,dialog}=require('electron');
// Some Windows composite camera drivers deliver black frames through Media Foundation.
// Use Chromium's DirectShow capture path; rendering and video encoding stay accelerated.
if(process.platform==='win32'){
  const disabled=app.commandLine.getSwitchValue('disable-features').split(',').filter(Boolean);
  if(!disabled.includes('MediaFoundationVideoCapture'))disabled.push('MediaFoundationVideoCapture');
  app.commandLine.appendSwitch('disable-features',disabled.join(','));
}
const fs=require('node:fs');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const {Controller}=require('./controller.cjs');
const {NativeLocation}=require('./native-location.cjs');
const {MediaLease}=require('./media-permission.cjs');
const Updates=require('./updates.cjs');
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
  let updateCheck=null,updateDownload=null,completedUpdate=null;
  let incomingNotice,messageNotice;
  const mediaLease=new MediaLease();
  if(process.platform==='win32')app.setAppUserModelId('Chat');
  app.on('second-instance',()=>{window?.show();window?.focus();});
  app.whenReady().then(()=>{
    const entry=path.join(__dirname,'ui','index.html');
    // Chromium upper-cases the drive letter of a file: URL while Node keeps the case it was
    // launched with, so both sides are folded before comparing, exactly as Windows paths are.
    const entryUrl=pathToFileURL(entry).href.toLowerCase();
    controller=new Controller(app.getPath('userData'),safeStorage,event=>{
      if(!window||window.isDestroyed())return;
      if(event?.type!=='message')window.webContents.send(event?.type?.startsWith('call')?'chat:call':'chat:event',event);
      if(event?.type==='message'&&!window.isFocused()&&event.generation===controller.generation&&event.server===controller.server&&event.username===controller.user){
        window.flashFrame(true);
        try{messageNotice?.close();if(Notification.isSupported()){
          messageNotice=new Notification({title:'Chat 新消息',body:`${event.peer} 发来了新消息`,silent:false});
          messageNotice.on('click',()=>{if(window.isMinimized())window.restore();window.show();window.focus();});messageNotice.show();
        }}catch{}
      }else if(event?.type==='call'&&event.event?.action==='offer'){
        window.flashFrame(true);
        try{
          incomingNotice?.close();
          if(Notification.isSupported()){
            incomingNotice=new Notification({title:'Chat 来电',body:`${event.event.from} 邀请你${event.event.mode==='video'?'视频':'语音'}通话`,silent:false});
            incomingNotice.on('click',()=>{if(window.isMinimized())window.restore();window.show();window.focus();});
            incomingNotice.show();
          }
        }catch{ /* Taskbar flash remains available if system notifications are blocked. */ }
      }else if(event?.type==='call_end'||event?.type==='call_error'){
        incomingNotice?.close();incomingNotice=undefined;window.flashFrame(false);
      }
      if(!event?.type&&!event?.username){messageNotice?.close();messageNotice=undefined;}
    });
    window=new BrowserWindow({width:1040,height:900,minWidth:560,minHeight:650,show:false,title:'Chat',
      icon:path.join(__dirname,'assets','icon.ico'),
      webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,devTools:!app.isPackaged}});
    window.removeMenu();window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.on('focus',()=>{window.flashFrame(false);controller.setForeground(true);});
    window.webContents.on('will-navigate',event=>event.preventDefault());
    const trusted=event=>event.sender===window.webContents&&event.senderFrame===window.webContents.mainFrame&&event.senderFrame?.url?.toLowerCase()===entryUrl;
    const updateServer=()=>Updates.origin(controller.snapshot(false).selectedServer||controller.server);
    const updateCurrent=server=>{try{return updateServer()===server;}catch{return false;}};
    const cancelUpdate=()=>{updateCheck?.abort();updateDownload?.abort();completedUpdate=null;};
    ipcMain.handle('chat:check-update',async event=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      cancelUpdate();const server=updateServer(),abort=new AbortController();updateCheck=abort;
      try{const info=await Updates.checkUpdate(server,app.getVersion(),abort.signal);if(abort.signal.aborted||!updateCurrent(server))throw Error('更新服务器已变化，请重新检查');return info;}
      finally{if(updateCheck===abort)updateCheck=null;}
    });
    ipcMain.handle('chat:download-update',async event=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      if(updateDownload)throw Error('已有更新包正在下载');
      const server=updateServer(),abort=new AbortController();updateDownload=abort;completedUpdate=null;
      try{
        const info=await Updates.checkUpdate(server,app.getVersion(),abort.signal);
        if(!info.available)throw Error('当前已是最新版本');
        if(!updateCurrent(server))throw Error('更新服务器已变化，请重新检查');
        const selected=await dialog.showSaveDialog(window,{title:'保存 Windows 更新包',defaultPath:path.join(app.getPath('downloads'),info.fileName),buttonLabel:'下载更新包',filters:[{name:'ZIP 更新包',extensions:['zip']}]});
        if(selected.canceled||!selected.filePath)return {canceled:true};
        if(abort.signal.aborted||!updateCurrent(server))throw Error('更新服务器已变化，请重新检查');
        const saved=await Updates.downloadUpdate(server,info,selected.filePath,{signal:abort.signal,isCurrent:()=>updateCurrent(server),onProgress:progress=>{
          if(!abort.signal.aborted&&updateCurrent(server)&&window&&!window.isDestroyed())window.webContents.send('chat:update-progress',{server,...progress});
        }});
        if(abort.signal.aborted||!updateCurrent(server))throw Error('更新服务器已变化，请重新检查');
        completedUpdate={server,file:saved};return {server,fileName:path.basename(saved)};
      }finally{if(updateDownload===abort)updateDownload=null;}
    });
    ipcMain.handle('chat:cancel-update',async event=>{if(!trusted(event))throw Error('IPC sender rejected');updateDownload?.abort();return true;});
    ipcMain.handle('chat:open-download',async event=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      if(!completedUpdate||!updateCurrent(completedUpdate.server)||!fs.existsSync(completedUpdate.file))throw Error('没有可打开的更新包');
      shell.showItemInFolder(completedUpdate.file);return true;
    });
    ipcMain.handle('chat:open-map',async(event,latitude,longitude)=>{
      if(event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame||event.senderFrame?.url?.toLowerCase()!==entryUrl)throw new Error('IPC sender rejected');
      if(typeof latitude!=='number'||!Number.isFinite(latitude)||Math.abs(latitude)>90||
         typeof longitude!=='number'||!Number.isFinite(longitude)||Math.abs(longitude)>180)throw new Error('位置坐标无效');
      // Only numeric coordinates reach this fixed HTTPS endpoint; renderer text is never a URL.
      const url=new URL('https://uri.amap.com/marker');
      url.searchParams.set('position',`${longitude},${latitude}`);
      url.searchParams.set('coordinate','wgs84');
      url.searchParams.set('src','Chat');
      await shell.openExternal(url.href);
    });
    ipcMain.handle('chat:open-updates',async event=>{
      if(event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame||event.senderFrame?.url?.toLowerCase()!==entryUrl)throw new Error('IPC sender rejected');
      await shell.openExternal('https://github.com/wobuyaoquminga/chatapp-secure/releases/latest');
    });
    const geoAllowed=(contents,permission,details)=>['geolocation','geolocation-approximate'].includes(permission)&&contents===window.webContents&&Date.now()<geoUntil&&window.isFocused()&&details?.isMainFrame===true&&details.requestingUrl?.toLowerCase()===entryUrl;
    const mediaAllowed=(contents,permission,details)=>permission==='media'&&contents===window.webContents&&window.isFocused()&&mediaLease.allows({entryUrl,requestingUrl:details?.requestingUrl,isMainFrame:details?.isMainFrame,mediaTypes:details?.mediaTypes||(details?.mediaType?[details.mediaType]:null),server:controller.server,generation:controller.generation,call:controller.call});
    session.defaultSession.setPermissionRequestHandler((contents,permission,callback,details)=>callback(geoAllowed(contents,permission,details)||mediaAllowed(contents,permission,details)));
    session.defaultSession.setPermissionCheckHandler((contents,permission,_origin,details)=>geoAllowed(contents,permission,details)||mediaAllowed(contents,permission,details));
    window.on('blur',()=>{controller.setForeground(false);geoUntil=0;mediaLease.revoke();nativeLocation.cancel();window.webContents.send('chat:location-stop');controller.serial(()=>controller.stopLocations()).catch(()=>{});});
    let closing=false;window.on('close',event=>{cancelUpdate();geoUntil=0;mediaLease.revoke();nativeLocation.cancel();if(closing)return;event.preventDefault();closing=true;window.webContents.send('chat:location-stop');Promise.race([controller.serial(()=>controller.stopLocations()),new Promise(resolve=>setTimeout(resolve,2000))]).catch(()=>{}).finally(()=>window.destroy());});
    ipcMain.handle('chat:command',async(event,{action,payload})=>{
      if(event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame||event.senderFrame?.url?.toLowerCase()!==entryUrl)throw new Error('IPC sender rejected');
      if((action==='sendLocation'||action==='send'&&payload?.body?.startsWith(require('./ui/features.js').PREFIX))&&!window.isFocused()&&require('./ui/features.js').parse(payload?.body)?.kind!=='stop')return {ok:false,error:'请在应用前台发送位置'};
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
      if(action==='callMediaPermission'){
        try{
          if(!window.isFocused()||!controller.online||!controller.call?.userApproved||controller.call.peer!==payload?.peer||controller.call.callId!==payload?.callId||controller.call.mode!==payload?.mode)throw new Error('通话媒体授权已失效');
          if(controller.normalizeServer(controller.server)!==controller.server||controller.callPeer(payload.peer)!==controller.call.accountId)throw new Error('通话服务器或联系人身份已变化');
          mediaLease.grant({server:controller.server,generation:controller.generation,callId:payload.callId,mode:payload.mode});return {ok:true,value:true};
        }catch(e){return {ok:false,error:e.message};}
      }
      if(action==='revokeCallMediaPermission'){mediaLease.revoke();return {ok:true,value:true};}
      if(action==='appInfo')return {ok:true,value:{version:app.getVersion()}};
      if(!['beginCall','approveCall','cancelCall','sendCall','callIce','sendLocation','stopLocations','login','send','retryMessage','networkRestored','history','safety','logout','forgetAccount','forgetServer','saveServer','selectServer','addContact','acceptContact','removeContact','clearConversation','openConversation','refreshContacts','snapshot'].includes(action))throw new Error('Unsupported command');
      if(['saveServer','selectServer','forgetServer'].includes(action))cancelUpdate();
      return controller.serial(async()=>{
        try{if(['logout','login','saveServer','selectServer','forgetServer'].includes(action)){geoUntil=0;mediaLease.revoke();nativeLocation.cancel();await controller.stopLocations();}return {ok:true,value:await controller[action](payload)}}catch(e){return {ok:false,error:e.message}}
      });
    });
    window.loadFile(entry);window.once('ready-to-show',()=>{if(!process.argv.includes('--test-hidden'))window.show();});
  });
  app.on('window-all-closed',()=>app.quit());
  app.on('before-quit',()=>{mediaLease.revoke();nativeLocation.cancel();controller?.logout();});
}
