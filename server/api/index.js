const { handler } = require('../build/dist/server.js');

// Vercel serverless function entry point
// Uses Node.js http module pattern: handler(req, res)
module.exports = handler;
