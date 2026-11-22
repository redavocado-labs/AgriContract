import { Readable } from 'node:stream';
import { PDF, buildMail } from '../../../test/support/mail';
import { readMail } from './mime-reader';

const MiB = 1024 * 1024;
const read = (raw: Buffer, cap = 20 * MiB) =>
  readMail(Readable.from([raw]), cap);

describe('readMail (MIME, streaming)', () => {
  it('reads the headers, the text and each attachment in order', async () => {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3]);
    const mail = await read(
      buildMail({
        from: 'Reports@SGS.example.com',
        subject: 'Report CASE-7',
        messageId: '<abc@sgs.example.com>',
        authenticationResults: [
          'mx.agricontract.local; spf=pass smtp.mailfrom=sgs.example.com; dkim=pass header.d=sgs.example.com',
        ],
        text: 'Report attached.',
        attachments: [
          {
            filename: 'report.pdf',
            contentType: 'application/pdf',
            bytes: PDF,
          },
          { filename: 'photo.png', contentType: 'image/png', bytes: png },
        ],
      }),
    );

    expect(mail).toMatchObject({
      messageId: '<abc@sgs.example.com>',
      fromAddress: 'Reports@SGS.example.com',
      subject: 'Report CASE-7',
      authenticationResults: [expect.stringContaining('spf=pass')],
    });
    expect(mail.text.trim()).toBe('Report attached.');
    expect(mail.attachments).toEqual([
      { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      { filename: 'photo.png', contentType: 'image/png', bytes: png },
    ]);
  });

  it('an attachment over the cap is only marked tooLarge; the others still come', async () => {
    const mail = await read(
      buildMail({
        attachments: [
          {
            filename: 'big.pdf',
            contentType: 'application/pdf',
            bytes: Buffer.alloc(3000, 1),
          },
          { filename: 'ok.pdf', contentType: 'application/pdf', bytes: PDF },
        ],
      }),
      1000,
    );

    expect(mail.attachments).toEqual([
      { filename: 'big.pdf', contentType: 'application/pdf', tooLarge: true },
      { filename: 'ok.pdf', contentType: 'application/pdf', bytes: PDF },
    ]);
  });

  it('exactly the cap is accepted', async () => {
    const bytes = Buffer.alloc(1000, 2);
    const mail = await read(
      buildMail({
        attachments: [
          { filename: 'a.pdf', contentType: 'application/pdf', bytes },
        ],
      }),
      1000,
    );

    expect(mail.attachments[0]).toMatchObject({ bytes });
  });

  it('an image embedded in the HTML body is not an attachment', async () => {
    const mail = await read(
      buildMail({ inlineImage: Buffer.from([0x89, 0x50, 0x4e, 0x47]) }),
    );

    expect(mail.attachments).toEqual([]);
  });

  it('no Message-ID, no From: nulls', async () => {
    const raw = Buffer.from('Subject: hi\r\n\r\nbody\r\n');

    const mail = await read(raw);

    expect(mail).toMatchObject({
      messageId: null,
      fromAddress: null,
      subject: 'hi',
      attachments: [],
    });
  });

  it('a broken source stream rejects with its own error (a retry, not an intake failure)', async () => {
    const broken = new Error('IMAP connection lost');
    const source = new Readable({
      read() {
        this.push(Buffer.from('Subject: x\r\n'));
        this.destroy(broken);
      },
    });

    await expect(readMail(source, MiB)).rejects.toBe(broken);
  });
});
