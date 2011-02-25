var http=require('http');
var webSocketServer=require('./webSocketServer');

function handleSocket(s) {
	console.log('Handling web socket');
	s.start();
}

var server=http.createServer(function(req, res) {
	var ws=res.webSocket;
	if (ws) {
		handleSocket(ws);
	} else {
		// Punt - we don't do anything else here
		res.writeHead(500);
		res.end('We only speak WebSockets here');
	}
});

webSocketServer.configureServer(server);

server.listen(4080);
console.log('Server running');

