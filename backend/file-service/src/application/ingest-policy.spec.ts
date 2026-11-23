import {
  DIRECT_UPLOAD_POLICY,
  SYSTEM_GENERATED_POLICY,
  normalizeContentType,
} from './ingest-policy';

describe('DIRECT_UPLOAD_POLICY (file-service-phase2-design.md §3)', () => {
  it('accepts JPG, PNG and PDF only, up to 10 MiB, and scans', () => {
    expect(DIRECT_UPLOAD_POLICY).toEqual({
      channel: 'DIRECT_UPLOAD',
      maxBytes: 10 * 1024 * 1024,
      contentTypes: ['image/jpeg', 'image/png', 'application/pdf'],
      virusScan: true,
    });
  });
});

describe('SYSTEM_GENERATED_POLICY (store(), §3, D88)', () => {
  it('accepts PDF only, up to 10 MiB, with no scan', () => {
    expect(SYSTEM_GENERATED_POLICY).toEqual({
      channel: 'SYSTEM_GENERATED',
      maxBytes: 10 * 1024 * 1024,
      contentTypes: ['application/pdf'],
      virusScan: false,
    });
  });
});

describe('normalizeContentType', () => {
  it.each([
    ['image/jpeg', 'image/jpeg'],
    ['IMAGE/PNG', 'image/png'],
    ['application/pdf; charset=binary', 'application/pdf'],
    ['  text/csv ;header=present', 'text/csv'],
    [
      'application/vnd.google-earth.kml+xml',
      'application/vnd.google-earth.kml+xml',
    ],
  ])('%p -> %p', (raw, expected) => {
    expect(normalizeContentType(raw)).toBe(expected);
  });

  it.each(['', 'pdf', 'image/', '/png', 'image/png/extra', 'image png'])(
    'rejects %p',
    (raw) => {
      expect(normalizeContentType(raw)).toBeNull();
    },
  );
});
