'use strict';
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const repo=path.resolve(__dirname,'..');
const ignored=new Set(['node_modules','test','qa','package.cjs','package-lock.json','package.json']);
async function verifyPackagedClient(source,archive,version){
 const {extractFile}=await import(pathToFileURL(path.join(repo,'client/node_modules/@electron/asar/lib/asar.js')).href);
 const expected=JSON.parse(fs.readFileSync(path.join(source,'package.json'),'utf8'));
 const packaged=JSON.parse(extractFile(archive,'package.json').toString('utf8'));
 if(expected.version!==version||packaged.version!==version||packaged.main!==expected.main)
  throw Error('Windows package is stale: version or entry point does not match source');
 if(JSON.stringify(packaged.dependencies)!==JSON.stringify(expected.dependencies))
  throw Error('Windows package dependencies do not match source');
 let count=0;
 function walk(relative=''){
  for(const entry of fs.readdirSync(path.join(source,relative),{withFileTypes:true})){
   if(!relative&&ignored.has(entry.name))continue;
   const child=relative?relative+'/'+entry.name:entry.name;
   if(entry.isSymbolicLink())throw Error('Client source symlink refused: '+child);
   if(entry.isDirectory())walk(child);
   else if(entry.isFile()){
    if(!extractFile(archive,child).equals(fs.readFileSync(path.join(source,child))))
     throw Error('Windows package contains stale source: '+child);
    count++;
   }
  }
 }
 walk();return count;
}
if(require.main===module){
 const version=JSON.parse(fs.readFileSync(path.join(repo,'release.json'),'utf8')).windows;
 verifyPackagedClient(path.join(repo,'client'),path.join(repo,'release/Chat-win32-x64/resources/app.asar'),version)
  .then(count=>console.log(`Windows package ${version}: ${count} production files match source`))
  .catch(error=>{console.error(error.message);process.exitCode=1;});
}
module.exports={verifyPackagedClient};
