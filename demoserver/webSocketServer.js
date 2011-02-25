var http=require('http');
var EventEmitter=require('events').EventEmitter;
var inspect=require('util').inspect;
var crypto=require('crypto');

/**
 * Standard protocol implementation.  Some of this was taken/inspired from Socket.IO:
 *   https://github.com/LearnBoost/Socket.IO-node/blob/master/lib/socket.io/transports/websocket.js
 * @param {Object} webSocket
 */
function StandardProtocol(webSocket) {
	this.webSocket=webSocket;
	
	// Setup events
	var cn=webSocket.connection;
	cn.setNoDelay(true);
	cn.on('data', this.ondata.bind(this));
	cn.on('end', this.onend.bind(this));
	cn.on('close', this.onclose.bind(this));
	
	// Deal with the headers
	var headers=webSocket.req.headers;
	this.key1=headers['sec-websocket-key1'];
	this.key2=headers['sec-websocket-key2'];
	this.key3=new Buffer(8);
	this.key3.received=0;
	this.handshaked=false;
	
	this.headBuffer=new Buffer(10);
	this.headBuffer.received=0;
	
	// Need to send the 101 response immediately so that some
	// proxy servers will clear us to receive the nonce (which is
	// not per http spec and is therefore held back)
	this.writeResponseHead();
}
StandardProtocol.prototype={};
StandardProtocol.prototype.validateHandshake=function() {
	var cn=this.webSocket.connection;
	var key1=this.key1, key2=this.key2;
	var md5=crypto.createHash('md5');
	
	if (!key1 || !key2) return false;
	hashKey(key1);
	hashKey(key2);
	md5.update(this.key3);
	
	// Write the challenge response
	cn.write(md5.digest('binary'), 'binary');
	return true;
	
	function hashKey(k) {
		var n=parseInt(k.replace(/[^\d]/g, '')), spaces=k.replace(/[^ ]/g, '').length;
		if (spaces===0 || n % spaces !==0) return false;
		n/=spaces;
		md5.update(new Buffer([n>>24 & 0xff, n>>16 & 0xff, n>>8 & 0xff, n & 0xff]));
	}
};

StandardProtocol.prototype.writeResponseHead=function() {
	var cn=this.webSocket.connection;
	var headers=[
		'HTTP/1.1 101 WebSocket',
		'Upgrade: WebSocket',
		'Connection: Upgrade'
	];
	
	var addlHeaders=this.webSocket.responseHeaders;
	if (addlHeaders) {
		Object.keys(addlHeaders).forEach(function(k) {
			headers.push(k + ': ' + String(addlHeaders[k]));
		});
	}
	
	headers.push('');
	headers.push('');
	
	cn.write(headers.join('\r\n'));
};

StandardProtocol.prototype.ondata=function(data) {
	if (!this.handshaked) {
		this.ondataHandshake(data);
		return;
	}
	
	console.log('Got data: ' + data);
	
	
};
StandardProtocol.prototype.ondataHandshake=function(data) {
	// Accumulate into key3 until full
	var key3=this.key3;
	var remain=key3.length-key3.received;
	if (data.length<remain) remain=data.length;
	data.copy(key3, key3.received, 0, remain);
	key3.received+=remain;
	if (key3.received!=key3.length) {
		// Still need more
		return;
	}
	data=data.slice(remain);
	
	// Validate the handshake
	if (!this.validateHandshake()) {
		this.webSocket.abort();
		return;
	}
	this.handshaked=true;
	
	
	// Send the sliced data back through for main processing
	if (data.length>0) this.ondata(data);
};

StandardProtocol.prototype.onend=function() {
	
};
StandardProtocol.prototype.onclose=function() {
	
};

function WebSocket(req, connection, startBuffer) {
	this.req=req;
	this.connection=connection;
	this.startBuffer=startBuffer;
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
};

/**
 * Starts the WebSocket.  Call this in lieu of doing your own processing
 * on the connection.  Emits the 'start' event.
 */
WebSocket.prototype.start=function() {
	this.emit('start');
	
	// Detect protocol version
	var protocol;
	var headers=this.req.headers;
	var draftVersion=headers['sec-websocket-draft'];
	if (draftVersion=='2' || draftVersion=='3') {
		protocol=new StandardProtocol(this);
	} else {
		// TODO: Fallback to draft 76
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
exports.isWebSocketRequest=isWebSocketRequest;
exports.configureServer=configureServer;
