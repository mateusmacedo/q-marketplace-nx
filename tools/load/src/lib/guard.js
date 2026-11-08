const LOCAL_HOSTS = ['localhost', '127.0.0.1'];
const URL_FORMAT = /^(https?):\/\/([^/:?#\s]+)(?::(\d{1,5}))?(\/[^?#\s]*)?$/i;

// WHY: a suíte cria dados, loga com as senhas do realm de demonstração e satura o alvo; só o compose local é alvo
// seguro sem decisão explícita de quem roda.
export function assertTarget(url, name, allowRemote) {
  const match = URL_FORMAT.exec(url);
  if (!match) {
    throw new Error(`guard: ${name} inválida, esperado http://<host>[:porta] (recebido "${url}")`);
  }
  const [, scheme, host] = match;
  const local = scheme.toLowerCase() === 'http' && LOCAL_HOSTS.includes(host.toLowerCase());
  if (!local && !allowRemote) {
    throw new Error(
      `guard: ${name} fora de http://localhost e http://127.0.0.1 (recebido "${url}"); para outro alvo, defina ALLOW_REMOTE_TARGET=true`,
    );
  }
  return url.replace(/\/$/, '');
}
