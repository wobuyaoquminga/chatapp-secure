const {spawn}=require('node:child_process');const path=require('node:path');
// Fixed script: no renderer text, server data or coordinates enter the command line.
const SCRIPT=`$ErrorActionPreference='Stop'
$watcher=$null
try {
 Add-Type -AssemblyName System.Device
 $watcher=New-Object System.Device.Location.GeoCoordinateWatcher ([System.Device.Location.GeoPositionAccuracy]::High)
 $started=$watcher.TryStart($false,[TimeSpan]::FromSeconds(12))
 if($watcher.Permission -eq [System.Device.Location.GeoPositionPermission]::Denied){@{error='denied'}|ConvertTo-Json -Compress;exit 0}
 $position=$watcher.Position
 if(!$started -or $position.Location.IsUnknown){@{error='unavailable'}|ConvertTo-Json -Compress;exit 0}
 $location=$position.Location
 @{coords=@{latitude=$location.Latitude;longitude=$location.Longitude;accuracy=$location.HorizontalAccuracy};timestamp=$position.Timestamp.ToUnixTimeMilliseconds()}|ConvertTo-Json -Compress
} catch { @{error='unavailable'}|ConvertTo-Json -Compress }
finally { if($null -ne $watcher){$watcher.Stop();$watcher.Dispose()} }
`;
function validatePosition(value,now=Date.now()){
 if(!value||!value.coords||!Number.isFinite(value.timestamp)||value.timestamp<now-120000||value.timestamp>now+300000)throw new Error('系统位置已过期，请重新获取位置。');
 const {latitude,longitude,accuracy}=value.coords;if(!Number.isFinite(latitude)||Math.abs(latitude)>90||!Number.isFinite(longitude)||Math.abs(longitude)>180||!Number.isFinite(accuracy)||accuracy<0||accuracy>100000)throw new Error('系统定位数据不可用，请重试或手动发送固定坐标。');
 return {coords:{latitude,longitude,accuracy},timestamp:value.timestamp};
}
class NativeLocation {
 constructor({spawnProcess=spawn,timeoutMs=15000,guardInterval=100}={}){this.spawnProcess=spawnProcess;this.timeoutMs=timeoutMs;this.guardInterval=guardInterval;this.pending=null;}
 cancel(){this.pending?.cancel();}
 query(authorized){
  if(this.pending)return Promise.reject(new Error('正在获取位置，请稍候。'));
  if(!authorized())return Promise.reject(new Error('当前聊天无定位权限，请重新点击获取位置。'));
  return new Promise((resolve,reject)=>{
   let child,timer,guard,done=false,output='';const finish=(error,value)=>{if(done)return;done=true;clearTimeout(timer);clearInterval(guard);this.pending=null;try{child?.kill();}catch{};error?reject(error):resolve(value);};
   const cancelled=()=>finish(new Error('定位已取消；应用必须在线并处于前台。'));this.pending={cancel:cancelled};
   try{child=this.spawnProcess(path.join(process.env.SystemRoot||'C:\\Windows','System32','WindowsPowerShell','v1.0','powershell.exe'),['-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(SCRIPT,'utf16le').toString('base64')],{windowsHide:true,stdio:['ignore','pipe','ignore']});}catch{finish(new Error('Windows 系统定位服务不可用，请手动发送固定坐标。'));return;}
   child.stdout.on('data',chunk=>{output+=chunk.toString('utf8');if(output.length>4096)finish(new Error('系统定位返回无效数据。'));});
   child.on('error',()=>finish(new Error('Windows 系统定位服务不可用，请手动发送固定坐标。')));
   child.on('close',()=>{if(done)return;if(!authorized())return cancelled();try{const value=JSON.parse(output.trim());if(value.error==='denied')throw new Error('Windows 已拒绝定位权限，请检查系统位置设置或手动发送固定坐标。');if(value.error)throw new Error('此设备的系统定位服务不可用，请手动发送固定坐标。');finish(null,validatePosition(value));}catch(e){finish(new Error(e instanceof SyntaxError?'系统定位返回无效数据。':e.message));}});
   timer=setTimeout(()=>finish(new Error('系统定位超时，可重试或手动发送固定坐标。')),this.timeoutMs);
   guard=setInterval(()=>{if(!authorized())cancelled();},this.guardInterval);
  });
 }
}
module.exports={NativeLocation,validatePosition,SCRIPT};
