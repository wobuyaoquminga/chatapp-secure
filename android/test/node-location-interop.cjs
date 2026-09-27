'use strict';
const fs = require('node:fs');
const features = require('../../client/ui/features.js');
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
Date.now = () => request.now;
process.stdout.write(JSON.stringify(request.bodies.map(body => Boolean(features.parse(body)))));
