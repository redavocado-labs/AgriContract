/**
 * The EICAR test file (eicar.org): harmless, and every scanner reports it as
 * a virus. Assembled at runtime so no source file holds it whole, which a
 * workstation antivirus would quarantine.
 */
export const EICAR = Buffer.from(
  [
    'X5O!P%@AP[4\\PZX54(P^)7CC)7}$',
    'EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*',
  ].join(''),
  'latin1',
);
