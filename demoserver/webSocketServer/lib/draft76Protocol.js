var crypto=require('crypto');

// -- Constants
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
function Protocol(webSocket) {
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
	this.rxframe=null;
	
	// Default to 1MB max message size
	this.maxMessageSize=2<<20;
	
	// reset rx state
	this.setState(STATE_CONNHEADER);
	
	// Need to send the 101 response immediately so that some
	// proxy servers will clear us to receive the nonce (which is
	// not per http spec and is therefore held back)
	this.writeResponseHead();
}


Protocol.prototype={};
Protocol.prototype.version='draft76';

// -- public Protocol api
Protocol.prototype.sendTextMessage=function(text) {
	var msgBuffer;
	if (Buffer.isBuffer(text)) msgBuffer=text;
	else msgBuffer=new Buffer(text, 'utf8');
	if (msgBuffer.length>this.maxMessageSize)
		throw new Error('Attempt to send WebSocket message larger than maxMessageSize');
		
	
	var bufferHead=new Buffer(1);
	var bufferTail=new Buffer(1);
	bufferHead[0]=0x00;
	bufferTail[0]=0xff;
	
	this.transmitMessage([
		bufferHead,
		msgBuffer,
		bufferTail]);
};

Protocol.prototype.sendBinaryMessage=function(buffer) {
	throw new Error('sendBinaryMessage not supported in this protocol version');
};

Protocol.prototype.initiateClose=function() {
	if (this.closing) return;
	this.closing=true;
	var buffer=new Buffer(2);
	buffer[0]=0xff;
	buffer[1]=0x00;
	this.transmitMessage([buffer]);
};

// -- private Protocol impl
/**
 * RX States:
 *   0 = Reading connection header
 *   1 = Reading frame header
 *   2 = Reading frame
 */
Protocol.prototype.setState=function(state, varlen) {
	var rxbuffer, rxframe;
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
		rxbuffer.needed=1;
		break;
	case STATE_FRAMEBODY:
		rxbuffer=null;
		rxframe=[];
		break;
	}
	
	this.rxbuffer=rxbuffer;
	this.rxframe=rxframe;
	this.rxstate=state;
	debug('setState ' + state + ', rxbuffer.needed=' + (rxbuffer ? rxbuffer.needed : 0));
};
Protocol.prototype.validateHandshake=function(key3) {
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

Protocol.prototype.writeResponseHead=function() {
	var cn=this.webSocket.connection;
	var headers=[
		'HTTP/1.1 101 WebSocket',
		'Upgrade: WebSocket',
		'Connection: Upgrade'
	];
	
	var addlHeaders=this.webSocket.responseHeaders||{};
	if (addlHeaders) {
		Object.keys(addlHeaders).forEach(function(k) {
			headers.push(k + ': ' + String(addlHeaders[k]));
		});
	}
	
	headers.push('');
	headers.push('');
	
	cn.write(headers.join('\r\n'));
};

Protocol.prototype.serviceTxqueue=function() {
	var cn=this.webSocket.connection;
	var txqueue=this.txqueue;
	var item;
	var buffer;
	var i;
	
	while (this.txenabled && txqueue.length && this.txdraining<this.mtu) {
		item=txqueue.shift();
		for (i=0; i<item.length; i++) {
			buffer=item[i];
			if (!cn.write(buffer)) {
				this.txdraining+=buffer.length;
			}
		}
		debug('Transmitted frame');
		
		// Special case.  If we just sent a close message, stop transmission
		if (item[0] && item[0][0]===0xff) {
			this.txenabled=false;
			this.closeOnTimeout(20000);
			debug('CLOSE message sent.  Transmission disabled');
		}
	}
};

/**
 * Adds a message buffer to the transmission queue.
 * @param {Object} buffer
 */
Protocol.prototype.transmitMessage=function(bufferAry) {
	this.txqueue.push(bufferAry);
	if (this.txqueue.length===1) this.serviceTxqueue();
};

/**
 * Adds a message to the front of the transmission queue
 * @param {Object} buffer
 */
Protocol.prototype.transmitMessageImmediate=function(bufferAry) {
	this.txqueue.unshift(bufferAry);
	if (this.txqueue.length===1) this.serviceTxqueue();
}

Protocol.prototype.ondata=function(data) {
	while (data&&data.length) {
		if (this.rxstate===STATE_FRAMEBODY) {
			// We're just accumulating until we get an end of frame 0xff
			var rxframe=this.rxframe, frameByte, text;
			for (var dataIndex=0; dataIndex<data.length; dataIndex++) {
				frameByte=data[dataIndex];
				if (frameByte!==0xff) rxframe[rxframe.length++]=frameByte;
				else {
					// End of frame
					this.webSocket.emit('message', new Buffer(rxframe));
					this.setState(STATE_FRAMEHEADER);
					data=data.slice(dataIndex+1);
					break;	// Continue the outer while loop
				}
			}
		} else {
			// We are doing generic reads
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
			}
			
			if (!ok) {
				this.rxbuffer=null;
				this.webSocket.abort();
				return;
			}
		}
	}
};
Protocol.prototype.onstateConnHeader=function(rxbuffer) {
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

Protocol.prototype.onstateFrameHeader=function(headerBuffer) {
	if (headerBuffer.received===1) {
		// Is it a text frame or close
		if (headerBuffer[0]===0x00) {
			// Text frame
			this.setState(STATE_FRAMEBODY);
			return true;
		} else if (headerBuffer[0]===0xff) {
			// First byte of close
			headerBuffer.needed=2;
			return true;
		}
	} else if (headerBuffer.received===2 && headerBuffer[1]===0x00) {
		// Full close frame received
		if (this.closing) {
			// All done
			return false;
		} else {
			this.closing=true;
			var closeMessage=new Buffer(2);
			closeMessage[0]=0xff;
			closeMessage[1]=0x00;
			this.transmitMessageImmediate([closeMessage]);
			return true;
		}			
	}
	
	warn('Internal error with header buffer length');
	return false;
};

Protocol.prototype.closeOnTimeout=function(timeout) {
	if (this.closingOnTimeout) return;
	this.closingOnTimeout=true;
	
	if (!timeout) timeout=20000;
	var self=this;
	// Set a close timer to make sure we clean up
	var cn=this.webSocket.connection;
	cn.on('timeout', function() {
		warn('Timeout waiting for WebSocket close handshake.  Force closing connection.');
		self.webSocket.abort();
	});
	cn.setTimeout(timeout);
};

Protocol.prototype.onend=function() {
	debug('socket end');
	// Half closed connections don't imply close on end
	this.webSocket.abort();
};
Protocol.prototype.onclose=function() {
	debug('socket close');
};
Protocol.prototype.ondrain=function() {
	debug('Socket drained');
	var self=this;
	this.txdraining=0;
	this.serviceTxqueue();
};



module.exports=Protocol;
