const compositionRoot = '^src/(?:config(?:/|$)|[^/]+\\.ts$)';
const sharedEventing = '^\\.\\./shared/node-eventing/';
const frameworkPackages =
  '(?:@nestjs|@golevelup|@aws-sdk)/|(?:@agricontract/eventing|mysql2|typeorm|knex|amqplib|amqp-connection-manager|nodemailer|ioredis|redis|cheerio)(?:/|$)';

module.exports = {
  forbidden: [
    {
      name: 'domain-not-to-outer',
      severity: 'error',
      from: { path: '^src/domain/' },
      to: { path: ['^src/(?:application|infrastructure)/', compositionRoot] },
    },
    {
      name: 'domain-no-framework',
      severity: 'error',
      from: { path: '^src/domain/' },
      to: { path: [`(?:^|/)node_modules/(?:${frameworkPackages})`, sharedEventing] },
    },
    {
      name: 'application-not-to-infra',
      severity: 'error',
      from: { path: '^src/application/' },
      to: { path: ['^src/infrastructure/', compositionRoot] },
    },
    {
      name: 'no-circular',
      severity: 'error',
      from: {},
      to: { circular: true },
    },
  ],
  // src/config and source files at the root of src are composition roots.
  // They may wire any layer. Domain and application cannot import them.
  options: {
    doNotFollow: { path: `(^|/)node_modules/|${sharedEventing}` },
    exclude: { path: '(^|/)test/|\\.(?:int-)?spec\\.ts$' },
    tsPreCompilationDeps: true,
  },
};
