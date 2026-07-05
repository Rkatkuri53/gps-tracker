// ============================================
// GPS Tracker — Viewer Logic
// ============================================

// State
let socket = null;
let map = null;
let marker = null;
let trail = null;
let trailCoords = [];
let sessionId = null;
let autoFollow = true;
let totalDistance = 0;
let lastPosition = null;
let prevMarker = null;
let startedAt = null;
let durationInterval = null;

// Initialize
document.addEventListener('DOMContentLoaded', init);

function init() {
  // Extract session ID from URL
  const pathParts = window.location.pathname.split('/');
  sessionId = pathParts[pathParts.length - 1];

  if (!sessionId) {
    showError('Invalid tracking link');
    return;
  }

  initMap();
  initSocket();
}

// ============================================
// Map Initialization
// ============================================

function initMap() {
  map = L.map('map', {
    center: [20, 78],
    zoom: 5,
    zoomControl: true,
    attributionControl: true
  });

  // CartoDB Voyager tiles — highly reliable for WebViews, no strict user-agent blocking
  L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; OpenStreetMap &copy; CARTO',
    maxZoom: 19
  }).addTo(map);

  // Detect manual pan to disable auto-follow
  map.on('dragstart', () => {
    autoFollow = false;
    document.getElementById('centerBtn').style.opacity = '1';
  });
}

// ============================================
// Socket.io Connection
// ============================================

function initSocket() {
  socket = io();

  socket.on('connect', () => {
    console.log('Connected to server');
    updateStatus('connecting', 'Joining session...');
    socket.emit('join-session', { sessionId });
  });

  socket.on('disconnect', () => {
    updateStatus('error', 'Disconnected — reconnecting...');
    document.getElementById('liveBadge').style.display = 'none';
  });

  // Receive initial session data
  socket.on('session-data', (data) => {
    console.log('Session data received:', data);
    startedAt = data.startedAt;

    if (!data.isActive) {
      updateStatus('error', 'Tracking has ended');
      showEndedOverlay(data);
      return;
    }

    updateStatus('active', 'Tracking live');
    document.getElementById('liveBadge').style.display = 'flex';

    // Replay history
    if (data.locations && data.locations.length > 0) {
      data.locations.forEach((loc, index) => {
        trailCoords.push([loc.latitude, loc.longitude]);
        if (index > 0) {
          const prev = data.locations[index - 1];
          totalDistance += calculateDistance(
            prev.latitude, prev.longitude,
            loc.latitude, loc.longitude
          );
        }
      });

      // Show trail
      updateTrail();

      // Show last known position
      const lastLoc = data.locations[data.locations.length - 1];
      updateMarker(lastLoc.latitude, lastLoc.longitude, lastLoc.heading);
      updateStatsDisplay(lastLoc.speed, lastLoc.timestamp);
      map.setView([lastLoc.latitude, lastLoc.longitude], 16, { animate: false });
    }

    // Start duration counter
    durationInterval = setInterval(updateDuration, 1000);
  });

  // Receive live location updates
  socket.on('location-update', (data) => {
    const { latitude, longitude, speed, heading, timestamp } = data;

    // Calculate distance
    if (lastPosition) {
      const dist = calculateDistance(
        lastPosition.lat, lastPosition.lng,
        latitude, longitude
      );
      if (dist > 0.001) { // > 1 meter
        totalDistance += dist;
      }
    }
    lastPosition = { lat: latitude, lng: longitude };

    // Update marker
    updateMarker(latitude, longitude, heading);

    // Update trail
    trailCoords.push([latitude, longitude]);
    updateTrail();

    // Update stats
    updateStatsDisplay(speed, timestamp);

    // Auto-follow
    if (autoFollow) {
      map.setView([latitude, longitude], map.getZoom(), { animate: false });
    }
  });

  // Tracking stopped
  socket.on('tracking-stopped', (data) => {
    updateStatus('error', 'Tracking ended');
    document.getElementById('liveBadge').style.display = 'none';
    if (durationInterval) {
      clearInterval(durationInterval);
    }
    showEndedOverlay({
      locations: trailCoords.map(c => ({ latitude: c[0], longitude: c[1] })),
      startedAt: startedAt
    });
  });

  // Error
  socket.on('error', (data) => {
    showError(data.message);
  });

  socket.on('connect_error', () => {
    updateStatus('error', 'Connection failed — retrying...');
  });
}

// ============================================
// Map Updates
// ============================================

function updateMarker(lat, lng, heading) {
  // Update Previous Marker
  if (marker) {
    if (!prevMarker) {
      const prevHtml = `
        <div class="tracker-marker viewer-marker" style="opacity: 0.7;">
          <div class="marker-dot" style="background: #ff9f43; width: 14px; height: 14px;"></div>
        </div>
      `;
      const prevIcon = L.divIcon({
        html: prevHtml,
        className: 'custom-marker',
        iconSize: [14, 14],
        iconAnchor: [7, 7]
      });
      prevMarker = L.marker(marker.getLatLng(), { icon: prevIcon }).addTo(map);
    } else {
      prevMarker.setLatLng(marker.getLatLng());
    }
  }

  // Update Current Marker
  if (!marker) {
    const markerHtml = `
      <div class="tracker-marker viewer-marker">
        <div class="marker-pulse"></div>
        <div class="marker-dot"></div>
      </div>
    `;
    const icon = L.divIcon({
      html: markerHtml,
      className: 'custom-marker',
      iconSize: [18, 18],
      iconAnchor: [9, 9]
    });
    marker = L.marker([lat, lng], { icon }).addTo(map);
  } else {
    marker.setLatLng([lat, lng]);
  }
}

function updateTrail() {
  if (trail) {
    trail.setLatLngs(trailCoords);
  } else {
    trail = L.polyline(trailCoords, {
      color: '#00cec9',
      weight: 4,
      opacity: 0.7,
      smoothFactor: 1,
      lineCap: 'round',
      lineJoin: 'round'
    }).addTo(map);
  }
}

// ============================================
// Stats Updates
// ============================================

function updateStatsDisplay(speed, timestamp) {
  // Speed
  const speedKmh = speed ? (speed * 3.6).toFixed(1) : '0.0';
  document.getElementById('speedValue').textContent = speedKmh;

  // Distance
  document.getElementById('distanceValue').textContent = totalDistance.toFixed(2);

  // Last update
  if (timestamp) {
    const ago = Math.floor((Date.now() - timestamp) / 1000);
    document.getElementById('lastUpdateValue').textContent = ago < 5 ? 'now' : `${ago}s ago`;
  }
}

function updateDuration() {
  if (!startedAt) return;
  const elapsed = Math.floor((Date.now() - startedAt) / 1000);
  const hours = Math.floor(elapsed / 3600);
  const minutes = Math.floor((elapsed % 3600) / 60);
  const seconds = elapsed % 60;

  let display;
  if (hours > 0) {
    display = `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  } else {
    display = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }
  document.getElementById('durationValue').textContent = display;
}

// ============================================
// UI Controls
// ============================================

function centerOnTracker() {
  autoFollow = true;
  document.getElementById('centerBtn').style.opacity = '0.7';
  if (lastPosition) {
    map.setView([lastPosition.lat, lastPosition.lng], 16, { animate: false });
  } else if (trailCoords.length > 0) {
    const last = trailCoords[trailCoords.length - 1];
    map.setView(last, 16, { animate: false });
  }
}

function showEndedOverlay(data) {
  const overlay = document.getElementById('endedOverlay');
  overlay.style.display = 'flex';

  // Stats
  document.getElementById('endDistance').textContent = totalDistance.toFixed(2);
  document.getElementById('endPoints').textContent = trailCoords.length;

  if (startedAt) {
    const elapsed = Math.floor((Date.now() - startedAt) / 1000);
    const minutes = Math.floor(elapsed / 60);
    const seconds = elapsed % 60;
    document.getElementById('endDuration').textContent = `${minutes}:${String(seconds).padStart(2, '0')}`;
  }
}

function showError(message) {
  updateStatus('error', 'Error');
  const overlay = document.getElementById('errorOverlay');
  overlay.style.display = 'flex';
  if (message) {
    document.getElementById('errorMessage').textContent = message;
  }
}

// ============================================
// Utilities
// ============================================

function calculateDistance(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

function toRad(deg) {
  return deg * (Math.PI / 180);
}

function updateStatus(type, text) {
  const dot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  dot.className = 'status-dot';
  if (type === 'active') dot.classList.add('active');
  else if (type === 'connecting') dot.classList.add('connecting');
  else if (type === 'error') dot.classList.add('error');
  statusText.textContent = text;
}
