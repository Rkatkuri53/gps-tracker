var SESSION_ID = "__SESSION_ID__";
var map, marker, trail, socket;
var trailCoords = [];
var totalDistance = 0;
var lastPosition = null;
var startedAt = null;
var autoFollow = true;
var durationInterval = null;

window.onerror = function(m) { updateStatus('error', 'Error: ' + m); return false; };

function initMap() {
  try {
    if (typeof L === 'undefined') { updateStatus('error', 'Map library failed'); return; }
    map = L.map('map', { center: [20, 78], zoom: 5, zoomControl: true, attributionControl: false });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
    setTimeout(function() { map.invalidateSize(); }, 300);
    setTimeout(function() { map.invalidateSize(); }, 1000);
    setTimeout(function() { map.invalidateSize(); }, 3000);
    updateStatus('connecting', 'Connecting to server...');
  } catch (e) { updateStatus('error', 'Map: ' + e.message); }
}

function initSocket() {
  socket = io('https://gps-tracker-htzc.onrender.com', {
    transports: ['websocket', 'polling'],
    reconnection: true,
    reconnectionAttempts: Infinity,
    reconnectionDelay: 1000
  });

  socket.on('connect', function() {
    updateStatus('connecting', 'Joining session...');
    socket.emit('join-session', { sessionId: SESSION_ID });
  });

  socket.on('disconnect', function() {
    updateStatus('error', 'Disconnected - reconnecting...');
  });

  socket.on('connect_error', function() {
    updateStatus('error', 'Connection failed - retrying...');
  });

  socket.on('session-data', function(data) {
    startedAt = data.startedAt;
    if (!data.isActive) { updateStatus('error', 'Tracking has ended'); return; }
    updateStatus('active', 'Tracking live');
    if (data.locations && data.locations.length > 0) {
      data.locations.forEach(function(loc, i) {
        trailCoords.push([loc.latitude, loc.longitude]);
        if (i > 0) {
          var p = data.locations[i - 1];
          totalDistance += calcDist(p.latitude, p.longitude, loc.latitude, loc.longitude);
        }
      });
      updateTrail();
      var last = data.locations[data.locations.length - 1];
      updateMarker(last.latitude, last.longitude);
      updateStats(last.speed, last.timestamp);
      map.setView([last.latitude, last.longitude], 16, { animate: true });
    } else {
      updateStatus('connecting', 'Waiting for GPS signal...');
    }
    durationInterval = setInterval(updateDuration, 1000);
  });

  socket.on('location-update', function(d) {
    updateStatus('active', 'Tracking live');
    var lat = d.latitude, lng = d.longitude;
    if (lastPosition) {
      var dist = calcDist(lastPosition.lat, lastPosition.lng, lat, lng);
      if (dist > 0.001) totalDistance += dist;
    }
    lastPosition = { lat: lat, lng: lng };
    updateMarker(lat, lng);
    trailCoords.push([lat, lng]);
    updateTrail();
    updateStats(d.speed, d.timestamp);
    if (autoFollow) map.panTo([lat, lng], { animate: true, duration: 0.5 });
  });

  socket.on('tracking-stopped', function() {
    updateStatus('error', 'Tracking ended');
    if (durationInterval) clearInterval(durationInterval);
  });

  socket.on('error', function(d) {
    updateStatus('error', d.message || 'Session not found');
  });
}

function updateMarker(lat, lng) {
  if (!marker) {
    var ic = L.divIcon({
      html: '<div class="tracker-marker"><div class="marker-pulse"></div><div class="marker-dot"></div></div>',
      className: '',
      iconSize: [18, 18],
      iconAnchor: [9, 9]
    });
    marker = L.marker([lat, lng], { icon: ic }).addTo(map);
  } else {
    marker.setLatLng([lat, lng]);
  }
}

function updateTrail() {
  if (trail) trail.setLatLngs(trailCoords);
  else trail = L.polyline(trailCoords, { color: '#00cec9', weight: 4, opacity: 0.7, smoothFactor: 1 }).addTo(map);
}

function updateStats(speed, ts) {
  document.getElementById('speedValue').textContent = speed ? (speed * 3.6).toFixed(1) : '0.0';
  document.getElementById('distanceValue').textContent = totalDistance.toFixed(2);
  if (ts) {
    var a = Math.floor((Date.now() - ts) / 1000);
    document.getElementById('lastUpdateValue').textContent = a < 5 ? 'now' : a + 's ago';
  }
}

function updateDuration() {
  if (!startedAt) return;
  var e = Math.floor((Date.now() - startedAt) / 1000);
  var h = Math.floor(e / 3600), m = Math.floor((e % 3600) / 60), s = e % 60;
  document.getElementById('durationValue').textContent = h > 0
    ? h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
    : String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}

function calcDist(a1, o1, a2, o2) {
  var R = 6371;
  var dA = (a2 - a1) * Math.PI / 180;
  var dO = (o2 - o1) * Math.PI / 180;
  var x = Math.sin(dA / 2) * Math.sin(dA / 2) +
    Math.cos(a1 * Math.PI / 180) * Math.cos(a2 * Math.PI / 180) *
    Math.sin(dO / 2) * Math.sin(dO / 2);
  return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
}

function updateStatus(t, x) {
  var d = document.getElementById('statusDot');
  d.className = 'status-dot';
  if (t === 'active') d.classList.add('active');
  else if (t === 'error') d.classList.add('error');
  document.getElementById('statusText').textContent = x;
}

initMap();
if (SESSION_ID) { initSocket(); } else { updateStatus('error', 'No session ID'); }
