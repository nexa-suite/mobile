(() => {
  let map;
  let tileLayer;
  let driverPoint;
  let accuracyCircle;
  let tileTemplate = "";
  let offlineMessage = "Map tiles unavailable. Driver location remains visible.";
  let providerNotConfiguredMessage = "Map provider is not configured. Driver location remains visible.";
  const status = document.getElementById("map-status");

  function setStatus(message) {
    status.textContent = message;
    status.style.display = message ? "block" : "none";
  }

  window.setMapMessages = (offline, providerNotConfigured) => {
    offlineMessage = offline;
    providerNotConfiguredMessage = providerNotConfigured;
  };

  window.setTileUrlTemplate = (template) => {
    if (typeof template === "string" && template.startsWith("https://") &&
        ["{z}", "{x}", "{y}"].every((part) => template.includes(part))) {
      tileTemplate = template;
    } else {
      tileTemplate = "";
    }
    if (map) {
      if (tileLayer) {
        map.removeLayer(tileLayer);
        tileLayer = undefined;
      }
      addTiles();
    }
  };

  function addTiles() {
    if (!map) return;
    if (!tileTemplate) {
      setStatus(providerNotConfiguredMessage);
      return;
    }
    tileLayer = L.tileLayer(tileTemplate, {
      attribution: "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a>",
      maxZoom: 18,
      updateWhenIdle: true,
      updateWhenZooming: false,
      keepBuffer: 0,
      noWrap: true
    });
    tileLayer.on("tileerror", () => setStatus(offlineMessage));
    tileLayer.on("tileload", () => setStatus(""));
    tileLayer.addTo(map);
  }

  window.setDriverLocation = (latitude, longitude, accuracyMeters) => {
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
        !Number.isFinite(longitude) || longitude < -180 || longitude > 180 ||
        !Number.isFinite(accuracyMeters) || accuracyMeters < 0) return;

    if (!map) {
      map = L.map("map", {
        center: [latitude, longitude],
        zoom: 15,
        minZoom: 3,
        maxZoom: 18,
        zoomControl: true,
        updateWhenIdle: true,
        preferCanvas: true
      });
      addTiles();
      driverPoint = L.circleMarker([latitude, longitude], {
        radius: 8,
        className: "driver-point",
        color: "#ffffff",
        weight: 2,
        fillColor: "#1463d6",
        fillOpacity: 1
      }).addTo(map);
      accuracyCircle = L.circle([latitude, longitude], {
        radius: accuracyMeters,
        color: "#1463d6",
        weight: 1,
        fillColor: "#1463d6",
        fillOpacity: 0.12
      }).addTo(map);
      return;
    }

    const point = [latitude, longitude];
    driverPoint.setLatLng(point);
    accuracyCircle.setLatLng(point);
    accuracyCircle.setRadius(accuracyMeters);
    map.panTo(point, { animate: false });
  };

  window.clearDriverLocation = () => {
    if (map) {
      map.remove();
      map = undefined;
    }
    tileLayer = undefined;
    driverPoint = undefined;
    accuracyCircle = undefined;
    document.getElementById("map").replaceChildren();
    setStatus("");
  };
})();
