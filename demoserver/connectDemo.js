var connect=require('connect'),
	webSocketServer=require('./webSocketServer');

function echoServer(ws) {
	ws.on('message', function(body) {
		console.log('Got message: "' + body.toString('utf8') + '"');
		ws.sendTextMessage(body);
	});
	
	// Take ownership of the request
	ws.connect();
}


var router=connect.router(function(app) {
	app.get('/echoserver', function(req, res, next) {
		var ws=res.webSocket;
		if (ws) {
			echoServer(ws);
		} else {
			// Punt - we don't do anything else here
			res.writeHead(500);
			res.end('We only speak WebSockets here');
		}
	});
});

var server=connect.createServer(
	connect.favicon(),
	connect.logger(),
	connect.staticProvider(__dirname + '/public'),
	router
);

webSocketServer.configureServer(server);

server.listen(4080);
console.log('Server running');
