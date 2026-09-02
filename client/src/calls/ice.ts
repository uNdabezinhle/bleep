/** In-region TURN only. Never stun.l.google.com (T19 / T23). Names never go here. */
export function iceServers(): RTCIceServer[] {
  const host = location.hostname;
  const user = "bleep";
  const cred = "bleep-dev";
  return [
    { urls: `stun:${host}:3478` },
    { urls: `turn:${host}:3478?transport=udp`, username: user, credential: cred },
    { urls: `turn:${host}:3478?transport=tcp`, username: user, credential: cred },
  ];
}
