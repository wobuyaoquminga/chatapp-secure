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
const {ReverseGeocoder,validCoordinates}=require('./reverse-geocoding.cjs');
const {MediaLease}=require('./media-permission.cjs');
const Updates=require('./updates.cjs');
const Files=require('./file-transfer.cjs');
const FileFormat=require('./ui/file-format.js');
const MediaFormat=require('./media-format.cjs');
const crypto=require('node:crypto');
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
  let window,controller,geoUntil=0,geoPeer='',geoGeneration=-1,geoServer='',geoUser='',geoLease=0,geoLive=false;
  let geocodingKey='';
  try{const config=JSON.parse(fs.readFileSync(path.join(app.getPath('userData'),'geocoding.json'),'utf8'));if(config?.provider==='amap'&&typeof config.webServiceKey==='string')geocodingKey=config.webServiceKey;}catch{}
  const reverseGeocoder=new ReverseGeocoder({amapKey:geocodingKey});
  const revokeGeo=(clearCache=false)=>{geoUntil=0;geoPeer='';geoLive=false;geoLease++;nativeLocation.cancel();if(clearCache)reverseGeocoder.reset();else reverseGeocoder.cancel();};
  let updateCheck=null,updateDownload=null,completedUpdate=null;
  const fileTasks=new Set();let fileBusy=false,fileOwner=null,previewTask=null,previewSequence=0;
  const cancelFiles=()=>{
    ++previewSequence;
    for(const task of fileTasks)task.abort();
  };
  let incomingNotice,messageNotice;
  const mediaLease=new MediaLease();
  if(process.platform==='win32')app.setAppUserModelId('Chat');
  app.on('second-instance',()=>{window?.show();window?.focus();});
  app.whenReady().then(async()=>{
    const entry=path.join(__dirname,'ui','index.html');
    // Chromium upper-cases the drive letter of a file: URL while Node keeps the case it was
    // launched with, so both sides are folded before comparing, exactly as Windows paths are.
    const entryUrl=pathToFileURL(entry).href.toLowerCase();
    window=new BrowserWindow({width:1040,height:900,minWidth:560,minHeight:650,show:false,title:'Chat',
      icon:path.join(__dirname,'assets','icon.ico'),
      webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,devTools:!app.isPackaged}});
    window.removeMenu();window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    if(process.platform==='win32'){
      await window.loadURL('data:text/html;charset=utf-8,'+encodeURIComponent('<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'"><style>body{margin:0;height:100vh;display:grid;place-items:center;background:#edf2ee;color:#356858;font:16px system-ui}</style><p>正在准备安全存储…</p>'));
      if(!process.argv.includes('--test-hidden'))window.show();
      await require('./storage-ready.cjs').waitForStorageKey(app.getPath('sessionData'));
      if(window.isDestroyed())return;
    }
    controller=new Controller(app.getPath('userData'),safeStorage,event=>{
      if(fileOwner&&(fileOwner.generation!==controller.generation||fileOwner.server!==controller.server||fileOwner.user!==controller.user||fileOwner.accountId!==controller.accountId))cancelFiles();
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
    window.on('focus',()=>{window.flashFrame(false);controller.setForeground(true);});
    window.webContents.on('will-navigate',event=>event.preventDefault());
    const trusted=event=>event.sender===window.webContents&&event.senderFrame===window.webContents.mainFrame&&event.senderFrame?.url?.toLowerCase()===entryUrl;
    const fileContext=()=>({generation:controller.generation,server:controller.server,user:controller.user,accountId:controller.accountId});
    const fileCurrent=context=>context.generation===controller.generation&&context.server===controller.server&&context.user===controller.user&&context.accountId===controller.accountId&&!!controller.engine;
    const readSelectedFile=async filePath=>{const handle=await fs.promises.open(filePath,'r');try{const before=await handle.stat();if(!before.isFile()||before.size>FileFormat.MAX)throw Error('只支持不超过 10 MiB 的普通文件');const buffer=Buffer.alloc(before.size+1);let count=0;while(count<buffer.length){const {bytesRead}=await handle.read(buffer,count,buffer.length-count,count);if(!bytesRead)break;count+=bytesRead;}const after=await handle.stat();if(count!==before.size||after.size!==before.size||after.mtimeMs!==before.mtimeMs)throw Error('文件读取时已变化，请重新选择');return buffer.subarray(0,count);}finally{await handle.close();}};
    const fileTask=async(work,preview=false)=>{
      if(fileBusy)throw Error('已有文件操作正在进行');
      fileBusy=true;
      fileOwner=fileContext();
      const abort=new AbortController();
      fileTasks.add(abort);
      let settle;
      const done=preview?new Promise(resolve=>{settle=resolve;}):null;
      if(preview)previewTask={controller:abort,done};
      const timer=setTimeout(()=>abort.abort(),120000);
      try{return await work(abort.signal);}
      catch(error){
        if(abort.signal.aborted||['AbortError','TimeoutError'].includes(error.name))
          throw Error('文件操作已取消或超时，请重试');
        throw error;
      }finally{
        clearTimeout(timer);
        fileTasks.delete(abort);
        if(previewTask?.controller===abort)previewTask=null;
        fileBusy=false;
        fileOwner=null;
        settle?.();
      }
    };
    const fileMessage=(clientId,peer,context)=>{if(!window.isFocused()||!context.user||typeof clientId!=='string'||!/^[0-9a-f-]{36}$/i.test(clientId)||typeof peer!=='string')throw Error('文件请求无效');const message=Object.values(controller.engine?.state.messages||{}).find(item=>item.clientId===clientId&&((item.sender===context.user&&item.recipient===peer)||(item.sender===peer&&item.recipient===context.user)));const meta=FileFormat.parse(message?.body);if(!meta)throw Error('该消息没有可保存的文件');return meta;};
    const encryptedFile=async(meta,context,token,signal)=>{let blob=await Files.cacheGet(app.getPath('userData'),context.server,context.accountId,meta.id,meta);if(signal.aborted||!fileCurrent(context))throw Error('账号已变化，请重新操作');if(!blob)blob=await Files.download({server:context.server,token,id:meta.id,size:meta.size,signal});if(signal.aborted||!fileCurrent(context))throw Error('账号已变化，请重新操作');return blob;};
    ipcMain.handle('chat:send-file',async(event,peer,kind='file')=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      return fileTask(async signal=>{
        if(!['file','image','video'].includes(kind))throw Error('文件类型无效');
        const context=fileContext();if(!window.isFocused())throw Error('请在应用前台发送文件');
        const toAccountId=controller.filePeer(peer),token=controller.token;
        const filters=kind==='image'?[{name:'图片',extensions:['jpg','jpeg','png','gif','webp']}]:kind==='video'?[{name:'视频',extensions:['mp4','webm']}]:undefined;
        const picked=await dialog.showOpenDialog(window,{title:kind==='image'?'选择图片':kind==='video'?'选择视频':'选择要发送的文件',properties:['openFile'],...(filters?{filters}:{})});
        if(picked.canceled||!picked.filePaths?.length)return {canceled:true};
        if(signal.aborted||!fileCurrent(context)||controller.filePeer(peer)!==toAccountId)throw Error('聊天或账号已改变，请重新选择文件');
        const filePath=picked.filePaths[0],plain=await readSelectedFile(filePath);
        let id,encrypted;
        try{if(kind!=='file'){if(FileFormat.mediaKind(filePath)!==kind)throw Error('所选文件类型不符');MediaFormat.inspect(filePath,plain);}
          if(signal.aborted||!fileCurrent(context)||controller.filePeer(peer)!==toAccountId)throw Error('聊天或账号已改变，请重新发送');
          id=crypto.randomUUID();encrypted=Files.encrypt(plain,id);
        }finally{plain.fill(0);}
        const name=FileFormat.sanitizeName(path.basename(filePath));
        // Keep the upload once the descriptor has entered this account's durable history.
        const engine=controller.engine;
        let body;
        try{
          const receipt=await Files.upload({server:context.server,token,id,peer,toAccountId,blob:encrypted.blob,signal});
          if(signal.aborted||!fileCurrent(context)||controller.filePeer(peer)!==toAccountId)throw Error('聊天或账号已改变，请重新发送');
          body=FileFormat.encode({v:1,id,name,size:encrypted.blob.length-16,key:encrypted.key,iv:encrypted.iv,sha256:encrypted.sha256,expiresAt:receipt.expiresAt});
          const snapshot=await controller.serial(()=>{if(signal.aborted||!fileCurrent(context)||controller.filePeer(peer)!==toAccountId)throw Error('聊天或账号已改变，请重新发送');return controller.sendFileDescriptor({peer,body});});
          await Files.cachePut(app.getPath('userData'),context.server,context.accountId,id,encrypted.blob).catch(()=>{});
          return {snapshot};
        }catch(error){
          // A later failure must not delete a file already referenced by durable history/outbox.
          if(!body||!Object.values(engine?.state.messages||{}).some(message=>message.body===body))
            await Files.remove({server:context.server,token,id,signal:AbortSignal.timeout(5000)});
          throw error;
        }
      });
    });
    ipcMain.handle('chat:save-file',async(event,clientId,peer)=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      return fileTask(async signal=>{
        const context=fileContext(),token=controller.token,meta=fileMessage(clientId,peer,context);
        const selected=await dialog.showSaveDialog(window,{title:'保存文件',defaultPath:path.join(app.getPath('downloads'),meta.name),buttonLabel:'保存'});
        if(selected.canceled||!selected.filePath)return {canceled:true};
        if(signal.aborted||!fileCurrent(context))throw Error('账号已变化，请重新保存');
        const blob=await encryptedFile(meta,context,token,signal);
        if(signal.aborted||!fileCurrent(context))throw Error('账号已变化，请重新保存');
        const plain=Files.decrypt(blob,meta);
        if(!await Files.cacheGet(app.getPath('userData'),context.server,context.accountId,meta.id,meta))await Files.cachePut(app.getPath('userData'),context.server,context.accountId,meta.id,blob).catch(()=>{});
        const target=selected.filePath,temp=target+'.chat-'+crypto.randomUUID()+'.tmp';
        try{await fs.promises.writeFile(temp,plain,{flag:'wx',mode:0o600});if(signal.aborted||!fileCurrent(context))throw Error('账号已变化，请重新保存');await fs.promises.rename(temp,target);}
        finally{plain.fill(0);await fs.promises.rm(temp,{force:true}).catch(()=>{});}
        return {saved:true,name:meta.name};
      });
    });
    ipcMain.handle('chat:preview-file',async(event,clientId,peer)=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      const sequence=++previewSequence,requestContext=fileContext();
      if(previewTask){
        previewTask.controller.abort();
        await previewTask.done;
      }
      if(sequence!==previewSequence||!fileCurrent(requestContext))throw Error('预览已取消或账号已变化');
      return fileTask(async signal=>{
        const context=fileContext(),token=controller.token,meta=fileMessage(clientId,peer,context);
        if(!FileFormat.mediaKind(meta.name))throw Error('该文件不支持预览，请保存文件');
        const blob=await encryptedFile(meta,context,token,signal);
        const plain=Files.decrypt(blob,meta);
        try{
          const media=MediaFormat.inspect(meta.name,plain);
          if(signal.aborted||!fileCurrent(context))throw Error('账号已变化，请重新预览');
          await Files.cachePut(app.getPath('userData'),context.server,context.accountId,meta.id,blob).catch(()=>{});
          if(signal.aborted||!fileCurrent(context))throw Error('账号已变化，请重新预览');
          return {mime:media.mime,kind:media.kind,base64:plain.toString('base64')};
        }finally{plain.fill(0);}
      },true);
    });
    ipcMain.handle('chat:cancel-file-preview',event=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      ++previewSequence;
      previewTask?.controller.abort();
      return true;
    });
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
    ipcMain.handle('chat:reverse-location',async(event,latitude,longitude,peer)=>{
      if(!trusted(event))throw Error('IPC sender rejected');
      if(!validCoordinates(latitude,longitude))throw Error('位置坐标无效');
      const lease=geoLease,generation=controller.generation,server=controller.server,user=controller.user;
      const authorized=()=>lease===geoLease&&Date.now()<geoUntil&&peer===geoPeer&&generation===geoGeneration&&controller.generation===generation&&controller.server===server&&controller.server===geoServer&&controller.user===user&&controller.user===geoUser&&controller.online&&window&&!window.isDestroyed()&&window.isFocused()&&controller.contactState[peer]?.status==='accepted'&&!controller.engine?.state.identityChanges?.[peer]&&!controller.engine?.state.deletedPeers?.[peer];
      if(!authorized())throw Error('位置授权已失效');
      return reverseGeocoder.resolve(latitude,longitude,{live:geoLive,authorized});
    });
    ipcMain.handle('chat:open-updates',async event=>{
      if(event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame||event.senderFrame?.url?.toLowerCase()!==entryUrl)throw new Error('IPC sender rejected');
      await shell.openExternal('https://github.com/wobuyaoquminga/chatapp-secure/releases/latest');
    });
    const geoAllowed=(contents,permission,details)=>['geolocation','geolocation-approximate'].includes(permission)&&contents===window.webContents&&Date.now()<geoUntil&&window.isFocused()&&details?.isMainFrame===true&&details.requestingUrl?.toLowerCase()===entryUrl;
    const mediaAllowed=(contents,permission,details)=>permission==='media'&&contents===window.webContents&&window.isFocused()&&mediaLease.allows({entryUrl,requestingUrl:details?.requestingUrl,isMainFrame:details?.isMainFrame,mediaTypes:details?.mediaTypes||(details?.mediaType?[details.mediaType]:null),server:controller.server,generation:controller.generation,call:controller.call});
    session.defaultSession.setPermissionRequestHandler((contents,permission,callback,details)=>callback(geoAllowed(contents,permission,details)||mediaAllowed(contents,permission,details)));
    // Enumeration needs a video permission check to reveal device labels and IDs. Capture still
    // goes through the request handler above, which requires an approved foreground call lease.
    session.defaultSession.setPermissionCheckHandler((contents,permission,_origin,details)=>geoAllowed(contents,permission,details)||mediaAllowed(contents,permission,details)||
      (permission==='media'&&details?.mediaType==='video'&&contents===window.webContents&&MediaLease.trustedFrame({entryUrl,requestingUrl:details?.requestingUrl,isMainFrame:details?.isMainFrame})));
    window.on('blur',()=>{controller.setForeground(false);revokeGeo();mediaLease.revoke();window.webContents.send('chat:location-stop');controller.serial(()=>controller.stopLocations()).catch(()=>{});});
    let closing=false;window.on('close',event=>{cancelUpdate();cancelFiles();revokeGeo();mediaLease.revoke();if(closing)return;event.preventDefault();closing=true;window.webContents.send('chat:location-stop');Promise.race([controller.serial(()=>controller.stopLocations()),new Promise(resolve=>setTimeout(resolve,2000))]).catch(()=>{}).finally(()=>window.destroy());});
    ipcMain.handle('chat:command',async(event,{action,payload})=>{
      if(event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame||event.senderFrame?.url?.toLowerCase()!==entryUrl)throw new Error('IPC sender rejected');
      if((action==='sendLocation'||action==='send'&&payload?.body?.startsWith(require('./ui/features.js').PREFIX))&&!window.isFocused()&&require('./ui/features.js').parse(payload?.body)?.kind!=='stop')return {ok:false,error:'请在应用前台发送位置'};
      if(action==='nativePosition'){
        const generation=controller.generation,peer=payload?.peer;
        const authorized=()=>Date.now()<geoUntil&&peer===geoPeer&&generation===geoGeneration&&controller.generation===generation&&controller.online&&window&&!window.isDestroyed()&&window.isFocused()&&controller.contactState[peer]?.status==='accepted'&&!controller.engine?.state.identityChanges?.[peer]&&!controller.engine?.state.deletedPeers?.[peer];
        try{return {ok:true,value:await nativeLocation.query(authorized)};}catch(e){return {ok:false,error:e.message};}
      }
      if(action==='locationPermission'){
        if(!controller.online||!window.isFocused()||controller.contactState[payload?.peer]?.status!=='accepted'||controller.engine?.state.identityChanges?.[payload?.peer]||controller.engine?.state.deletedPeers?.[payload?.peer])return {ok:false,error:'请在在线且已接受的聊天中操作'};
        revokeGeo();geoPeer=payload.peer;geoGeneration=controller.generation;geoServer=controller.server;geoUser=controller.user;geoLive=!!payload?.live;geoUntil=Date.now()+(geoLive?3600000:30000);return {ok:true,value:true};
      }
      if(action==='revokeLocationPermission'){revokeGeo();return {ok:true,value:true};}
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
      if(['logout','login','saveServer','selectServer','forgetServer','forgetAccount'].includes(action))cancelFiles();
      return controller.serial(async()=>{
        try{if(['logout','login','saveServer','selectServer','forgetServer','forgetAccount'].includes(action)){revokeGeo(true);mediaLease.revoke();await controller.stopLocations();}return {ok:true,value:await controller[action](payload)}}catch(e){return {ok:false,error:e.message}}
      });
    });
    window.loadFile(entry);window.once('ready-to-show',()=>{if(!process.argv.includes('--test-hidden'))window.show();});
  }).catch(error=>{dialog.showErrorBox('无法打开 Chat',error.message);app.quit();});
  app.on('window-all-closed',()=>app.quit());
  app.on('before-quit',()=>{cancelFiles();mediaLease.revoke();revokeGeo(true);controller?.logout();});
}
