self.addEventListener("push", (event) => {
  event.waitUntil(
    self.registration.showNotification("Bleep", {
      body: "New mail is waiting on this device.",
      tag: "bleep-wake",
      renotify: true,
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      for (const client of list) {
        if (client.url.includes("/app") && "focus" in client) return client.focus();
      }
      if (self.clients.openWindow) return self.clients.openWindow("/app");
    }),
  );
});
