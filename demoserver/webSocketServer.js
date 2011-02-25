var http=require('http');
var EventEmitter=require('events').EventEmitter;
var inspect=require('util').inspect;
var crypto=require('crypto');

// -- Constants
const  
	 OPCODE_CONTINUATION=0
	,OPCODE_CLOSE=1
	,OPCODE_PING=2
	,OPCODE_PONG=3
	,OPCODE_TEXT=4
	,OPCODE_BINARY=5;
const 
	 STATE_CONNHEADER=0
	,STATE_FRAMEHEADER=1
	,STATE_FRAMEBODY=2;

function warn(msg) {
	console.log('WEBSOCKET WARNING: ' + msg);
}
function debug(msg) {
	console.log('WEBSOCKET DEBUG: ' + msg);
}

/**
 * Standard protocol implementation.  Some of this was inspired by Socket.IO:
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
	cn.on('drain', this.ondrain.bind(this));
	
	// Deal with the headers
	var headers=webSocket.req.headers;
	this.key1=headers['sec-websocket-key1'];
	this.key2=headers['sec-websocket-key2'];
	
	// Pre-allocate a buffer big enough to hold all headers
	this.scratchBuffer=new Buffer(16);
	this.txqueue=[];
	this.txenabled=false;
	this.txdraining=0;
	this.closing=false;
	this.mtu=1200;
	
	// Default to 1MB max message size
	this.maxMessageSize=2<<20;
	
	// reset rx state
	this.setState(STATE_CONNHEADER);
	
	// Need to send the 101 response immediately so that some
	// proxy servers will clear us to receive the nonce (which is
	// not per http spec and is therefore held back)
	this.writeResponseHead();
}

/**
 * Make a message header buffer for the given length and
 * header fields
 * @param {Object} header1
 * @param {Object} header2
 * @param {Object} length
 */
StandardProtocol.makeMessageHeader=function(header1, header2, length) {
	// Act differently based on length
	var message;
	if (length<126) {
		// Simple - fits in standard header
		header2=(header2&0x80) | (length&0x7f);
		message=new Buffer(2);
		message[0]=header1;
		message[1]=header2;
		return message;
	} else if (length<32768) {
		// Header takes two additional bytes
		header2=(header2&0x80) | 126;
		message=new Buffer(4);
		message[0]=header1;
		message[1]=header2;
		message[2]=(length>>8) & 0xff;
		message[3]=(length&0xff);
		return message;
	} else {
		// Header takes 8 additional length bytes
		// We just blindly copy the low 32 bits
		// The length needs to be constrained prior to this call
		// Presume max of 31bit length
		header2=(header2&0x80) | 127;
		message=new Buffer(length+10);
		message[0]=header1;
		message[1]=header2;
		message[2]=0;
		message[3]=0;
		message[4]=0;
		message[5]=0;
		message[6]=(length>>24) & 0xff;
		message[7]=(length>>16) & 0xff;
		message[8]=(length>>8) & 0xff;
		message[9]=(length&0xff);
		return message;
	}
};

StandardProtocol.prototype={};
StandardProtocol.prototype.version='draft03';

// -- public Protocol api
StandardProtocol.prototype.sendTextMessage=function(text) {
	var msgBuffer;
	if (Buffer.isBuffer(text)) msgBuffer=text;
	else msgBuffer=new Buffer(text, 'utf8');
	
	if (msgBuffer.length>this.maxMessageSize)
		throw new Error('Attempt to send WebSocket message larger than maxMessageSize');
		
	this.transmitMessage([
		StandardProtocol.makeMessageHeader(OPCODE_TEXT, 0, msgBuffer.length),
		msgBuffer]);	
};

StandardProtocol.prototype.sendBinaryMessage=function(buffer) {
	var msgBuffer;
	if (Buffer.isBuffer(text)) msgBuffer=text;
	else msgBuffer=new Buffer(text, 'utf8');
	
	if (msgBuffer.length>this.maxMessageSize)
		throw new Error('Attempt to send WebSocket message larger than maxMessageSize');
		
	this.transmitMessage([
		StandardProtocol.makeMessageHeader(OPCODE_BINARY, 0, msgBuffer.length),
		msgBuffer]);	
};

StandardProtocol.prototype.initiateClose=function() {
	if (this.closing) return;
	this.closeCookie='nodeserverclose';
	var buffer=new Buffer(this.closeCookie, 'ascii');
	this.closing=true;
	this.transmitMessage([
		StandardProtocol.makeMessageHeader(OPCODE_CLOSE, 0, buffer.length),
		buffer]);
};

// -- private Protocol impl
/**
 * RX States:
 *   0 = Reading connection header
 *   1 = Reading frame header
 *   2 = Reading frame
 */
StandardProtocol.prototype.setState=function(state, varlen) {
	var rxbuffer;
	switch(state) {
	case STATE_CONNHEADER:
		/**
		 * Prepare to receive the 8byte connection header
		 */
		rxbuffer=this.scratchBuffer;
		rxbuffer.received=0;
		rxbuffer.needed=8;
		break;
	case STATE_FRAMEHEADER:
		/**
		 * Prepare to receive the 2byte required header
		 */
		rxbuffer=this.scratchBuffer;
		rxbuffer.received=0;
		rxbuffer.needed=2;
		break;
	case STATE_FRAMEBODY:
		/**
		 * Receive up to varlen data into buffer
		 */
		rxbuffer=new Buffer(varlen);
		rxbuffer.received=0;
		rxbuffer.needed=varlen;
		break;
	}
	
	this.rxbuffer=rxbuffer;
	this.rxstate=state;
	debug('setState ' + state + ', rxbuffer.needed=' + rxbuffer.needed);
};
StandardProtocol.prototype.validateHandshake=function(key3) {
	var cn=this.webSocket.connection;
	var key1=this.key1, key2=this.key2;
	var md5=crypto.createHash('md5');
	
	if (!key1 || !key2) {
		warn('Missing keys in connection headers');
		return false;
	}
	
	debug('Computing challenge response from key1=' + key1 + ', key2=' + key2 + ', key3=' + key3);
	hashKey(key1);
	hashKey(key2);
	md5.update(key3);
	
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

StandardProtocol.prototype.serviceTxqueue=function() {
	var cn=this.webSocket.connection;
	var txqueue=this.txqueue;
	var item;
	var buffer;
	var i;
	
	while (this.txenabled && txqueue.length && this.txdraining<this.mtu) {
		item=txqueue.shift();
		for (i=0; i<item.length; i++) {
			buffer=item[i];
			cn.write(buffer);
			this.txdraining+=buffer.length;
		}
		debug('Transmitted frame');
		
		// Special case.  If we just sent a close message, stop transmission
		if (item[0] && item[0][0]===OPCODE_CLOSE) {
			this.txenabled=false;
			debug('CLOSE message sent.  Transmission disabled');
		}
	}
};

/**
 * Adds a message buffer to the transmission queue.
 * @param {Object} buffer
 */
StandardProtocol.prototype.transmitMessage=function(bufferAry) {
	this.txqueue.push(bufferAry);
	if (this.txqueue.length===1) this.serviceTxqueue();
};

/**
 * Adds a message to the front of the transmission queue
 * @param {Object} buffer
 */
StandardProtocol.prototype.transmitMessageImmediate=function(bufferAry) {
	this.txqueue.unshift(bufferAry);
	if (this.txqueue.length===1) this.serviceTxqueue();
}

StandardProtocol.prototype.ondata=function(data) {
	while (data&&data.length) {
		var rxbuffer=this.rxbuffer;
		if (!rxbuffer) break;
		
		var remain=rxbuffer.needed-rxbuffer.received;
		//debug('Received data ' + data.length + '. Need ' + remain + ' to complete state.');
		
		if (remain>data.length) {
			// data does not contain enough to fill buffer
			data.copy(rxbuffer, rxbuffer.received, 0, data.length);
			rxbuffer.received+=data.length;
			return;
		} else if (remain>0) {
			// There is enough data to satisfy rxbuffer.needed.
			// Read and fall through
			data.copy(rxbuffer, rxbuffer.received, 0, remain);
			rxbuffer.received+=remain;
			data=data.slice(remain);
		}
		
		debug('Complete buffer received for state ' + this.rxstate);
		
		var ok;
		switch (this.rxstate) {
		/**
		 * Reading connection handshake
		 */
		case STATE_CONNHEADER:
			ok=this.onstateConnHeader(rxbuffer);
			break;
			
		/**
		 * Reading frame header
		 */
		case STATE_FRAMEHEADER:
			ok=this.onstateFrameHeader(rxbuffer);
			break;
			
		/**
		 * Reading frame header
		 */
		case STATE_FRAMEBODY:
			ok=this.onstateFrameBody(rxbuffer);
			break;
		}
		
		if (!ok) {
			this.rxbuffer=null;
			this.webSocket.abort();
			return;
		}
	}
};
StandardProtocol.prototype.onstateConnHeader=function(rxbuffer) {
	// Key3 is just 8 bytes of the buffer
	var key3=rxbuffer.slice(0,8);
	
	// Validate the handshake
	if (!this.validateHandshake(key3)) {
		warn('Handshake validation failed');
		return false;
	}
	
	debug('Handshake validated');
	this.setState(STATE_FRAMEHEADER);
	
	// Start transmission
	this.txenabled=true;
	this.serviceTxqueue();
	
	return true;
};

StandardProtocol.prototype.onstateFrameHeader=function(headerBuffer) {
	var frameHeader;
	if (headerBuffer.needed===2) {
		// Decode initial header fields.  We may need more depending on
		// the short length
		var header1=headerBuffer[0];
		var header2=headerBuffer[1];
		frameHeader={
			more: (header1&0x80),
			rsv1: (header1&0x40),
			rsv2: (header1&0x20),
			rsv3: (header1&0x10),
			opcode: (header1&0x0f),
			rsv4: (header2&0x80),
			length: (header2&0x7f)
		};
		this.frameHeader=frameHeader;
		
		// If the length is 126, there are an additional 2 length bytes.
		// If 127, then an additional 8
		if (frameHeader.length===126) {
			headerBuffer.needed=4;
			return true;
		} else if (frameHeader.length===127) {
			headerBuffer.needed=10;
			return true;
		} else {
			// Short length
			this.setState(STATE_FRAMEBODY, frameHeader.length);
			return true;
		}
	}
	
	if (headerBuffer.needed===4) {
		// We already decoded frameHeader but there are two additional length bytes
		frameHeader=this.frameHeader;
		frameHeader.length=((headerBuffer[2]&0xff)<<8) | (headerBuffer[3]&0xff);
		this.setState(STATE_FRAMEBODY, frameHeader.length);
		return true; 
	}
	
	if (headerBuffer.needed===10) {
		// Just wholsale disallow messages negative or > 2GB
		if (headerBuffer[2]!==0 || headerBuffer[3]!==0 || headerBuffer[4]!==0 || headerBuffer[5]!==0 || (headerBuffer[6]&0x80)!==0) {
			warn('Disallowing incoming message > 2GB');
			return false;
		}
		
		frameHeader.length=((headerBuffer[6]&0xff)<<24) | ((headerBuffer[7]&0xff)<<16) | ((headerBuffer[8]&0xff)<<8) | (headerBuffer[9]&0xff);
		if (frameHeader.length>this.maxMessageSize) {
			warn('Disallowing incoming message greater than maxMessageSize');
			return false;
		}
		
		this.setState(STATE_FRAMEBODY, frameHeader.length);
		return true; 
	}
	
	warn('Internal error with header buffer length');
	return false;
};

StandardProtocol.prototype.onstateFrameBody=function(frameBuffer) {
	// At this point, this.frameHeader is valid and frameBuffer is totally
	// filled
	var frameHeader=this.frameHeader;
	var opcode=frameHeader.opcode;
	var webSocket=this.webSocket;
	
	// Dispatch based on opcode
	switch (opcode) {
	case OPCODE_TEXT:
	case OPCODE_BINARY:
		webSocket.emit('message', frameBuffer, frameHeader);
		break;
	case OPCODE_PING:
		webSocket.emit('ping', frameBuffer, frameHeader);
		this.transmitMessageImmediate([
			StandardProtocol.makeMessageHeader(OPCODE_PONG, 0, frameBuffer.length),
			frameBuffer]);
		break;
	case OPCODE_PONG:
		webSocket.emit('pong', frameBuffer, frameHeader);
		break;
	case OPCODE_CLOSE:
		if (this.closeCookie && frameBuffer.toString('ascii')===this.closeCookie) {
			// Received close-ACK.  Fully closed.
			return false;
		} else {
			// Received close request.  Respond with ACK.  Fully closed.
			this.transmitMessageImmediate([
				StandardProtocol.makeMessageHeader(OPCODE_CLOSE, 0, frameBuffer.length),
				frameBuffer]);
			return false;
		}
		break;
	default:
		if (webSocket.listeners('unknownMessage').length)
			webSocket.emit('unknownMessage', frameBuffer, frameHeader);
		else {
			warn('No unknownMessage handler for unrecognized opcode');
			return false;
		}
	}
	
	this.setState(STATE_FRAMEHEADER);
	return true;
};

StandardProtocol.prototype.onend=function() {
	debug('socket end');
	// Half closed connections don't imply close on end
	this.webSocket.abort();
};
StandardProtocol.prototype.onclose=function() {
	debug('socket close');
};
StandardProtocol.prototype.ondrain=function() {
	var self=this;
	this.txdraining=0;
	this.serviceTxqueue();
	
	if (this.closing && !this.txenabled) {
		// Set a close timer to make sure we clean up
		var cn=this.webSocket.connection;
		cn.on('timeout', function() {
			warn('Timeout waiting for WebSocket close handshake.  Force closing connection.');
			self.webSocket.abort();
		});
		cn.setTimeout(20000);
	}
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
exports.isWebSocketRequest=isWebSocketRequest;
exports.configureServer=configureServer;
