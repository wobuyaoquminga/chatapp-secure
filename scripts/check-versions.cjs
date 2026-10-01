const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const versions = JSON.parse(read('release.json'));
const pkg = JSON.parse(read('client/package.json'));
const lock = JSON.parse(read('client/package-lock.json'));
const android = read('android/app/build.gradle');
const pom = read('server/pom.xml');
const checks = [
  pkg.version === versions.windows,
  lock.version === versions.windows && lock.packages[''].version === versions.windows,
  android.match(/versionName\s+'([^']+)'/)?.[1] === versions.android,
  Number(android.match(/versionCode\s+(\d+)/)?.[1]) === versions.androidCode,
  pom.includes(`<artifactId>chat-server</artifactId><version>${versions.server}</version>`),
  read('README.md').includes(`v${versions.bundle}`)
];
if (checks.some(check => !check)) throw new Error('release.json and platform versions disagree');
console.log(`Version manifest verified: Chat v${versions.bundle}`);
