var http=require('http');
var EventEmitter=require('events').EventEmitter;
var standardProtocol=require('./standardProtocol');
var draft76Protocol=require('./draft76Protocol');

function WebSocket(req, connection, startBuffer) {
	this.req=req;
	this.connection=connection;
	this.startBuffer=startBuffer;
	this.responseHeaders={};
	
	// Apply defaults for required headers
	// User can customize
	var reqOrigin=req.headers['origin'];
	if (reqOrigin) {
		this.responseHeaders['sec-websocket-origin']=reqOrigin;
	}
	
	// Calculate location
	var location=[];
	if (false) location.push('wss://');	// TODO: Figure how to detect https from req
	else location.push('ws://');
	location.push(req.headers['host']);
	location.push(req.originalUrl || req.url);
	this.responseHeaders['sec-websocket-location']=location.join('');
	
	//console.log(require('util').inspect(req));
}
WebSocket.prototype=Object.create(EventEmitter.prototype);

/**
 * Immediately aborts the connection
 * @param {Object} error
 */
WebSocket.prototype.abort=function(error) {
	var socket=this.connection;
	socket.end();
	socket.destroy();
	
	if (error) this.emit('error', error);
	this.emit('close');
	this.protocol=null;
};

/**
 * Starts the WebSocket.  Call this in lieu of doing your own processing
 * on the connection.  Emits the 'start' event.
 */
WebSocket.prototype.connect=function() {
	if (this._connected) return;
	this._connected=true;
	
	this.emit('start');
	
	// Detect protocol version
	var protocol;
	var headers=this.req.headers;
	var draftVersion=headers['sec-websocket-draft'];
	if (draftVersion=='2' || draftVersion=='3') {
		protocol=new standardProtocol(this);
	} else {
		protocol=new draft76Protocol(this);
	}
	
	if (!protocol) {
		// Abort
		console.log('Unrecognized protocol version');
		this.abort();
		return;
	}
	this.protocol=protocol;

	// If any data came from startBuffer, pump it through the socket now
	var startBuffer=this.startBuffer;
	if (startBuffer && startBuffer.length) {
		this.connection.emit('data', startBuffer);
	}
};

WebSocket.prototype.sendTextMessage=function(textMessage) {
	var protocol=this.protocol;
	if (!protocol) throw new Error('Attempt to send message to WebSocket that is not connected');
	protocol.sendTextMessage(textMessage);
};

WebSocket.prototype.sendBinaryMessage=function(binaryMessage) {
	var protocol=this.protocol;
	if (!protocol) throw new Error('Attempt to send message to WebSocket that is not connected');
	protocol.sendBinaryMessage(binaryMessage);
};

WebSocket.prototype.close=function() {
	var protocol=this.protocol;
	if (!protocol) return;
	protocol.initiateClose();
}

function isWebSocketRequest(req) {
	var upgrade=req.headers['upgrade'];
	var connection=req.headers['connection'];
	return req.method=='GET' && upgrade && upgrade.match(/^WebSocket$/i) && connection && connection.match(/^Upgrade$/i);
}

function handleHttpUpgrade(server, req, socket, upgradeHead) {
	if (!isWebSocketRequest(req)) return false;
	
	// Pause events on the socket.  We'll resume if the websocket is started
	socket.pause();
	
	// Create a normal Http response object, augment it
	// and then push it through the stack.
	// We don't allow pipelining or keepalives on these, so the logic is simpler
	// than in the http module
	var res=new http.ServerResponse(req);
	res.assignSocket(socket);
	res.on('finish', function() {
		res.detachSocket(socket);
		socket.destroySoon();
	});
	
	var webSocket=new WebSocket(req, socket, upgradeHead);
	webSocket.on('start', function() {
		// Reset some socket parameters that were molested in
		// their passage through the http stack
		socket.setTimeout(0);
		socket.removeAllListeners('timeout');
		
		// Resume events
		socket.resume();
	});
	res.webSocket=webSocket;
	
	
	// Push it through the http stack
	server.emit('request', req, res);
	return true;
}

function configureServer(server) {
	server.on('upgrade', function(req, socket, upgradeHead) {
		if (!handleHttpUpgrade(server, req, socket, upgradeHead)) {
			// Destroy
			socket.end();
			socket.destroy();
		}
	});
}

// -- exports
exports.handleHttpUpgrade=handleHttpUpgrade;
exports.isWebSocketRequest=isWebSocketRequest;
exports.configureServer=configureServer;
