import { spawnSync } from 'node:child_process';
const args = process.argv.slice(2);
const windows = process.platform === 'win32';
const result = spawnSync(windows ? 'cmd.exe' : './gradlew',
  windows ? ['/d', '/s', '/c', 'gradlew.bat', ...args] : args,
  { stdio: 'inherit', env: process.env });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
