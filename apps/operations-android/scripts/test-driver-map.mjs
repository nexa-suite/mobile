import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { runInNewContext } from "node:vm";

const source = readFileSync(
  new URL("../app/src/main/assets/driver-map/driver-map.js", import.meta.url),
  "utf8",
);
const template = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

function mapHarness() {
  const calls = { tileLayers: [], removedLayers: [], removedMaps: 0 };
  const status = { textContent: "", style: {} };
  const window = {};
  const leaflet = {
    map() {
      return {
        removeLayer(layer) { calls.removedLayers.push(layer); },
        remove() { calls.removedMaps++; },
        setView() {},
        fitBounds() {},
      };
    },
    tileLayer(url) {
      const layer = { on() { return layer; }, addTo() { return layer; } };
      calls.tileLayers.push({ url, layer });
      return layer;
    },
    circleMarker(point) {
      const marker = {
        addTo() { return marker; },
        setLatLng(next) { point = next; },
        getLatLng() { return point; },
      };
      return marker;
    },
    circle() {
      const circle = {
        addTo() { return circle; },
        setLatLng() {},
        setRadius() {},
      };
      return circle;
    },
    latLngBounds() { return { pad() { return this; } }; },
  };
  runInNewContext(source, {
    window,
    L: leaflet,
    document: {
      getElementById(id) {
        return id === "map-status" ? status : { replaceChildren() {} };
      },
    },
  });
  return { window, calls, status };
}

test("GPS refresh keeps the existing tile layer when its provider is unchanged", () => {
  const { window, calls } = mapHarness();
  window.setTileUrlTemplate(template);
  window.setDriverLocation(-12.04, -77.03, 5);
  window.setTileUrlTemplate(template);
  window.setDriverLocation(-12.041, -77.031, 6);
  assert.equal(calls.tileLayers.length, 1);
  assert.equal(calls.removedLayers.length, 0);
});

test("changing or disabling the provider replaces only the tile layer", () => {
  const { window, calls, status } = mapHarness();
  window.setTileUrlTemplate(template);
  window.setDriverLocation(-12.04, -77.03, 5);
  const alternative = "https://tiles.example.test/{z}/{x}/{y}.png";
  window.setTileUrlTemplate(alternative);
  assert.equal(calls.tileLayers.length, 2);
  assert.equal(calls.removedLayers.length, 1);
  window.setTileUrlTemplate("");
  assert.equal(calls.removedLayers.length, 2);
  assert.equal(calls.removedMaps, 0);
  assert.match(status.textContent, /not configured/);
});

test("clearing a location removes the map and permits a fresh map on return", () => {
  const { window, calls } = mapHarness();
  window.setTileUrlTemplate(template);
  window.setDriverLocation(-12.04, -77.03, 5);
  window.clearDriverLocation();
  assert.equal(calls.removedMaps, 1);
  window.setTileUrlTemplate(template);
  window.setDriverLocation(-12.042, -77.032, 5);
  assert.equal(calls.tileLayers.length, 2);
});
