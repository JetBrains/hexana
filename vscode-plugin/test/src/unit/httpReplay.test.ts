import {expect} from 'chai';
import * as fs from 'fs';
import * as http from 'http';
import {AddressInfo} from 'net';
import * as os from 'os';
import * as path from 'path';
import {HTTP_REPLAY_MAX_RESPONSE_BYTES, sendHttpReplay, writeHttpRequestFile} from '../../../src/httpReplay';

/**
 * The extension-host half of replaying a request captured in a PCAP. The
 * webview waits on exactly one `http-replay-response` per request, so every
 * outcome - an answer, a redirect, a refused connection - has to come back as
 * a reply rather than a thrown error.
 */
describe('http replay', function () {
    let server: http.Server;
    let base: string;
    let received: {method?: string; url?: string; headers?: http.IncomingHttpHeaders; body?: string};

    before(async function () {
        server = http.createServer((req, res) => {
            const chunks: Buffer[] = [];
            req.on('data', (c: Buffer) => chunks.push(c));
            req.on('end', () => {
                received = {method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString()};
                if (req.url === '/big') {
                    res.writeHead(200, {'Content-Type': 'application/octet-stream'});
                    res.end(Buffer.alloc(HTTP_REPLAY_MAX_RESPONSE_BYTES + 4096, 0x61));
                    return;
                }
                if (req.url === '/moved') {
                    res.writeHead(302, {Location: '/echo'});
                    res.end();
                    return;
                }
                res.writeHead(201, {'X-Answer': '1', 'Content-Type': 'text/plain'});
                res.end('pong');
            });
        });
        await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
        base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });

    after(function () {
        server.close();
    });

    beforeEach(function () {
        received = {};
    });

    it('sends the edited request and reports the answer', async function () {
        const reply = await sendHttpReplay({
            requestId: 'r1',
            method: 'PATCH',
            url: `${base}/echo?id=7`,
            headers: [['X-Replay', 'yes'], ['Content-Type', 'application/json']],
            body: Array.from(Buffer.from('{"a":1}')),
        });

        expect(reply.type).to.equal('http-replay-response');
        expect(reply.requestId).to.equal('r1');
        expect(reply.error).to.equal('');
        expect(reply.status).to.equal(201);
        expect(Buffer.from(reply.body).toString()).to.equal('pong');
        expect(reply.bodyTruncated).to.equal(false);
        expect(reply.headers).to.deep.include(['x-answer', '1']);

        expect(received.method).to.equal('PATCH');
        expect(received.url).to.equal('/echo?id=7');
        expect(received.headers?.['x-replay']).to.equal('yes');
        expect(received.headers?.['content-length']).to.equal('7');
        expect(received.body).to.equal('{"a":1}');
    });

    it('reports a redirect instead of following it', async function () {
        const reply = await sendHttpReplay({requestId: 'r2', method: 'GET', url: `${base}/moved`, headers: [], body: []});

        expect(reply.status).to.equal(302);
        expect(received.url).to.equal('/moved');
    });

    it('keeps only the start of a huge response', async function () {
        const reply = await sendHttpReplay({requestId: 'r5', method: 'GET', url: `${base}/big`, headers: [], body: []});

        expect(reply.error).to.equal('');
        expect(reply.body.length).to.equal(HTTP_REPLAY_MAX_RESPONSE_BYTES);
        expect(reply.bodyTruncated).to.equal(true);
    });

    it('turns failures into an error reply', async function () {
        const refused = await sendHttpReplay({
            requestId: 'r3', method: 'GET', url: 'http://127.0.0.1:1/', headers: [], body: [],
        });
        expect(refused.requestId).to.equal('r3');
        expect(refused.error).to.not.equal('');

        const notHttp = await sendHttpReplay({requestId: 'r4', method: 'GET', url: 'file:///etc/hosts', headers: [], body: []});
        expect(notHttp.error).to.contain('Only http and https');
    });

    it('writes the request and its body file side by side', async function () {
        const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-replay-test-'));
        try {
            const requestPath = await writeHttpRequestFile({
                fileName: '../escape.http',
                text: 'POST http://h/ HTTP/1.1\n\n< ./body.bin\n',
                bodyFileName: 'body.bin',
                body: [0x1f, 0x8b, 0x00],
            }, root);

            expect(path.basename(requestPath)).to.equal('escape.http');
            expect(path.dirname(path.dirname(requestPath))).to.equal(root);
            expect(fs.readFileSync(requestPath, 'utf8')).to.contain('< ./body.bin');
            expect([...fs.readFileSync(path.join(path.dirname(requestPath), 'body.bin'))]).to.deep.equal([0x1f, 0x8b, 0x00]);
        } finally {
            fs.rmSync(root, {recursive: true, force: true});
        }
    });
});
