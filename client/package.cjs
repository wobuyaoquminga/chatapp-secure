'use strict';
const fs=require('node:fs'),path=require('node:path');
const ignored=[/^\/test(?:\/|$)/,/^\/qa(?:\/|$)/,/^\/package\.cjs$/,
 /\/node_modules\/@signalapp\/libsignal-client\/prebuilds\/(?:darwin-arm64|darwin-x64|linux-arm64|linux-x64|win32-arm64)(?:\/|$)/];
function copyNotices(directory){
 fs.copyFileSync(path.join(__dirname,'..','LICENSE'),path.join(directory,'LICENSE-Chat.txt'));
 fs.copyFileSync(path.join(__dirname,'..','NOTICE.md'),path.join(directory,'NOTICE-Chat.md'));
}
async function build(){
 const {packager}=await import('@electron/packager'),{listPackage,extractFile}=await import('@electron/asar');
 const outputs=await packager({dir:__dirname,name:'Chat',platform:'win32',arch:'x64',out:path.join(__dirname,'..','release'),overwrite:true,
  icon:path.join(__dirname,'assets','icon.ico'),ignore:ignored,
  afterCopy:[({buildPath})=>copyNotices(buildPath)],afterComplete:[({buildPath})=>copyNotices(buildPath)]});
 for(const output of outputs){
  const prebuilds=path.join(output,'resources','app.asar.unpacked','node_modules','@signalapp','libsignal-client','prebuilds');
  if(JSON.stringify(fs.readdirSync(prebuilds))!==JSON.stringify(['win32-x64']))throw Error('Package contains unexpected native platforms');
  const archive=path.join(output,'resources','app.asar'),entries=listPackage(archive);
  if(entries.some(file=>/prebuilds\/(?:darwin-|linux-|win32-arm64)/.test(file.replaceAll('\\','/'))))throw Error('ASAR contains unused native libraries');
  if(!extractFile(archive,'LICENSE-Chat.txt').equals(fs.readFileSync(path.join(__dirname,'..','LICENSE')))||!extractFile(archive,'NOTICE-Chat.md').equals(fs.readFileSync(path.join(__dirname,'..','NOTICE.md'))))throw Error('Application notices missing from ASAR');
  if(!fs.existsSync(path.join(output,'LICENSE'))||!fs.existsSync(path.join(output,'LICENSE-Chat.txt'))||!fs.existsSync(path.join(output,'NOTICE-Chat.md')))throw Error('Package license notices missing');
  console.log('Packaged '+output);
 }
}
if(require.main===module)build().catch(error=>{console.error(error);process.exitCode=1;});
module.exports={ignored,copyNotices};
