/**
 * Quick connectivity test for the Forge server.
 * Usage: node test-connection.mjs <railway-url>
 * Example: node test-connection.mjs undercroft-forge-server-production.up.railway.app
 */

const host = process.argv[2] || 'localhost:7000';
const isSecure = !host.startsWith('localhost');
const httpUrl = `${isSecure ? 'https' : 'http'}://${host}`;
const wsUrl = `${isSecure ? 'wss' : 'ws'}://${host}/game`;

console.log('=== Forge Server Connectivity Test ===\n');

// Test 1: Health check (HTTP)
console.log(`[1] Health check: GET ${httpUrl}/health`);
try {
    const res = await fetch(`${httpUrl}/health`);
    const body = await res.text();
    console.log(`    ✅ Status: ${res.status} — Response: "${body}"\n`);
} catch (e) {
    console.log(`    ❌ Failed: ${e.message}\n`);
    process.exit(1);
}

// Test 2: WebSocket connection
console.log(`[2] WebSocket: ${wsUrl}`);
try {
    const ws = new WebSocket(wsUrl);
    
    const result = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Timeout after 10s')), 10000);
        
        ws.onopen = () => {
            console.log('    ✅ Connected!\n');
        };
        
        ws.onmessage = (event) => {
            const msg = JSON.parse(event.data);
            console.log(`[3] Server sent: ${JSON.stringify(msg, null, 2)}\n`);
            
            if (msg.type === 'connected') {
                console.log(`    ✅ Session ID: ${msg.payload.sessionId}`);
                console.log('    ✅ WebSocket handshake complete!\n');
                clearTimeout(timeout);
                resolve(msg);
            }
        };
        
        ws.onerror = (err) => {
            clearTimeout(timeout);
            reject(new Error('WebSocket error'));
        };
        
        ws.onclose = (event) => {
            if (!event.wasClean) {
                clearTimeout(timeout);
                reject(new Error(`Connection closed: ${event.code} ${event.reason}`));
            }
        };
    });
    
    // Test 3: Send a dummy start_game to see what happens
    console.log('[4] Sending test start_game message...');
    const testPayload = {
        type: 'start_game',
        payload: {
            format: 'commander',
            deckList: ['1 Lightning Bolt', '1 Mountain'],
            commander: 'Lightning Bolt'
        }
    };
    ws.send(JSON.stringify(testPayload));
    
    // Wait for response
    await new Promise((resolve) => {
        ws.onmessage = (event) => {
            const msg = JSON.parse(event.data);
            console.log(`    Server response: ${JSON.stringify(msg, null, 2).substring(0, 500)}\n`);
            resolve();
        };
        setTimeout(resolve, 5000);
    });
    
    ws.close();
    console.log('=== All tests passed! ===');
    
} catch (e) {
    console.log(`    ❌ Failed: ${e.message}\n`);
    process.exit(1);
}
