// ============================================
// GPS Tracker — Tracker Logic
// ============================================

// State
let isTracking = false;
let sessionId = null;
let watchId = null;
let socket = null;
let map = null;
let marker = null;
let trail = null;
let trailCoords = [];
let startTime = null;
let totalDistance = 0;
let lastPosition = null;
let durationInterval = null;

// Settings
let settings = {
  updateInterval: 3000,
  highAccuracy: true,
  autoCenter: true,
  showTrail: true
};

// Initialize
document.addEventListener('DOMContentLoaded', init);

function init() {
  initMap();
  initSocket();
  registerServiceWorker();
}

// ============================================
// Map Initialization
// ============================================

function initMap() {
  map = L.map('map', {
    center: [20, 0],
    zoom: 3,
    zoomControl: true,
    attributionControl: true
  });

  // Dark-styled map tiles
  L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/">CARTO</a>',
    subdomains: 'abcd',
    maxZoom: 20
  }).addTo(map);

  // Remove default tile filter since we're using dark tiles
  document.querySelector('.leaflet-tile-pane').style.filter = 'none';
}

// ============================================
// Socket.io Connection
// ============================================

function initSocket() {
  socket = io();

  socket.on('connect', () => {
    console.log('Connected to server');
    updateStatus('connected', 'Connected — Ready to track');
  });

  socket.on('disconnect', () => {
    updateStatus('error', 'Disconnected from server');
  });

  socket.on('session-created', (data) => {
    sessionId = data.sessionId;
    const fullLink = `${window.location.origin}${data.shareLink}`;
    document.getElementById('shareLink').value = fullLink;
    document.getElementById('sharePanel').style.display = 'block';
    showToast('Tracking started! Share your link 📍');
  });

  socket.on('viewer-count', (data) => {
    const badge = document.getElementById('viewerBadge');
    const count = document.getElementById('viewerCount');
    if (data.count > 0) {
      badge.style.display = 'flex';
      count.textContent = data.count;
    } else {
      badge.style.display = 'none';
    }
  });

  socket.on('connect_error', () => {
    updateStatus('error', 'Connection failed — retrying...');
  });
}

// ============================================
// Tracking Control
// ============================================

function toggleTracking() {
  if (isTracking) {
    stopTracking();
  } else {
    startTracking();
  }
}

function startTracking() {
  if (!navigator.geolocation) {
    showToast('Geolocation is not supported by your browser');
    return;
  }

  // Request permission and start
  navigator.geolocation.getCurrentPosition(
    (position) => {
      isTracking = true;
      startTime = Date.now();
      totalDistance = 0;
      lastPosition = null;
      trailCoords = [];

      // Update UI
      const btn = document.getElementById('trackBtn');
      btn.classList.add('tracking');
      document.getElementById('btnText').textContent = 'Stop Tracking';
      document.getElementById('btnIcon').innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>';

      updateStatus('active', 'Tracking your location...');

      // Tell server to create session
      socket.emit('start-tracking');

      // Start watching position
      startWatchingPosition();

      // Start duration counter
      durationInterval = setInterval(updateDuration, 1000);

      // Handle initial position
      handlePosition(position);
    },
    (error) => {
      handleGPSError(error);
    },
    {
      enableHighAccuracy: settings.highAccuracy,
      timeout: 10000,
      maximumAge: 0
    }
  );
}

function stopTracking() {
  isTracking = false;

  // Stop GPS watch
  if (watchId !== null) {
    navigator.geolocation.clearWatch(watchId);
    watchId = null;
  }

  // Stop duration counter
  if (durationInterval) {
    clearInterval(durationInterval);
    durationInterval = null;
  }

  // Tell server
  if (sessionId) {
    socket.emit('stop-tracking', { sessionId });
  }

  // Update UI
  const btn = document.getElementById('trackBtn');
  btn.classList.remove('tracking');
  document.getElementById('btnText').textContent = 'Start Tracking';
  document.getElementById('btnIcon').innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/></svg>';

  document.getElementById('sharePanel').style.display = 'none';
  document.getElementById('viewerBadge').style.display = 'none';

  updateStatus('connected', 'Tracking stopped');
  showToast('Tracking stopped');

  sessionId = null;
}

// ============================================
// GPS Position Handling
// ============================================

function startWatchingPosition() {
  const options = {
    enableHighAccuracy: settings.highAccuracy,
    timeout: 15000,
    maximumAge: settings.updateInterval
  };

  watchId = navigator.geolocation.watchPosition(
    handlePosition,
    handleGPSError,
    options
  );
}

function handlePosition(position) {
  if (!isTracking) return;

  const { latitude, longitude, accuracy, speed, heading, altitude } = position.coords;
  const timestamp = position.timestamp;

  // Calculate distance
  if (lastPosition) {
    const dist = calculateDistance(
      lastPosition.lat, lastPosition.lng,
      latitude, longitude
    );
    // Only count if moved more than accuracy radius (filter GPS jitter)
    if (dist > (accuracy || 10) / 1000) {
      totalDistance += dist;
    }
  }
  lastPosition = { lat: latitude, lng: longitude };

  // Update map
  updateMapPosition(latitude, longitude, heading);

  // Update trail
  if (settings.showTrail) {
    trailCoords.push([latitude, longitude]);
    updateTrail();
  }

  // Update stats
  updateStats(speed, accuracy);

  // Send to server
  if (sessionId) {
    socket.emit('location-update', {
      sessionId,
      latitude,
      longitude,
      accuracy,
      speed: speed || 0,
      heading: heading || 0,
      altitude: altitude || 0,
      timestamp
    });
  }
}

function handleGPSError(error) {
  let message = 'GPS error';
  switch (error.code) {
    case error.PERMISSION_DENIED:
      message = 'Location permission denied. Please enable GPS.';
      break;
    case error.POSITION_UNAVAILABLE:
      message = 'Location unavailable. Check GPS settings.';
      break;
    case error.TIMEOUT:
      message = 'Location request timed out.';
      break;
  }
  showToast(message);
  updateStatus('error', message);
}

// ============================================
// Map Updates
// ============================================

function updateMapPosition(lat, lng, heading) {
  if (!marker) {
    // Create custom marker
    const markerHtml = `
      <div class="tracker-marker">
        <div class="marker-pulse"></div>
        <div class="marker-dot"></div>
        ${heading ? '<div class="marker-heading"></div>' : ''}
      </div>
    `;
    const icon = L.divIcon({
      html: markerHtml,
      className: 'custom-marker',
      iconSize: [18, 18],
      iconAnchor: [9, 9]
    });
    marker = L.marker([lat, lng], { icon }).addTo(map);
    map.setView([lat, lng], 16, { animate: true });
  } else {
    marker.setLatLng([lat, lng]);
    if (settings.autoCenter) {
      map.panTo([lat, lng], { animate: true, duration: 0.5 });
    }
  }

  // Update heading arrow rotation
  if (heading && marker.getElement()) {
    const headingEl = marker.getElement().querySelector('.marker-heading');
    if (headingEl) {
      headingEl.style.transform = `translateX(-50%) rotate(${heading}deg)`;
    }
  }
}

function updateTrail() {
  if (trail) {
    trail.setLatLngs(trailCoords);
  } else {
    trail = L.polyline(trailCoords, {
      color: '#6c5ce7',
      weight: 4,
      opacity: 0.7,
      smoothFactor: 1,
      dashArray: null,
      lineCap: 'round',
      lineJoin: 'round'
    }).addTo(map);
  }
}

// ============================================
// Stats Updates
// ============================================

function updateStats(speed, accuracy) {
  // Speed (m/s to km/h)
  const speedKmh = speed ? (speed * 3.6).toFixed(1) : '0.0';
  document.getElementById('speedValue').textContent = speedKmh;

  // Distance
  document.getElementById('distanceValue').textContent = totalDistance.toFixed(2);

  // Accuracy
  document.getElementById('accuracyValue').textContent = accuracy ? `±${Math.round(accuracy)}` : '--';
}

function updateDuration() {
  if (!startTime) return;
  const elapsed = Math.floor((Date.now() - startTime) / 1000);
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
// Utilities
// ============================================

function calculateDistance(lat1, lon1, lat2, lon2) {
  const R = 6371; // Earth's radius in km
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

function showToast(message) {
  const toast = document.getElementById('toast');
  document.getElementById('toastMessage').textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 3000);
}

function copyShareLink() {
  const linkInput = document.getElementById('shareLink');
  const btn = document.getElementById('copyBtn');

  navigator.clipboard.writeText(linkInput.value).then(() => {
    btn.classList.add('copied');
    btn.querySelector('span').textContent = 'Copied!';
    showToast('Link copied to clipboard! 📋');
    setTimeout(() => {
      btn.classList.remove('copied');
      btn.querySelector('span').textContent = 'Copy';
    }, 2000);
  }).catch(() => {
    // Fallback
    linkInput.select();
    document.execCommand('copy');
    showToast('Link copied!');
  });
}

function toggleSettings() {
  const panel = document.getElementById('settingsPanel');
  panel.classList.toggle('open');
}

function updateSettings() {
  settings.updateInterval = parseInt(document.getElementById('updateInterval').value);
  settings.highAccuracy = document.getElementById('gpsAccuracy').value === 'true';
  settings.autoCenter = document.getElementById('autoCenter').checked;
  settings.showTrail = document.getElementById('showTrail').checked;

  // Toggle trail visibility
  if (trail) {
    if (settings.showTrail) {
      trail.addTo(map);
    } else {
      trail.remove();
    }
  }

  // Restart GPS watch if tracking
  if (isTracking && watchId !== null) {
    navigator.geolocation.clearWatch(watchId);
    startWatchingPosition();
  }
}

// ============================================
// Service Worker Registration
// ============================================

function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js')
      .then((reg) => {
        console.log('Service Worker registered:', reg.scope);
      })
      .catch((error) => {
        console.log('Service Worker registration failed:', error);
      });
  }
}
