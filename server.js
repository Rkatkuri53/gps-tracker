const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const { v4: uuidv4 } = require('uuid');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' }
});

const PORT = process.env.PORT || 3000;

// In-memory session store
const sessions = new Map();

// Serve static files
app.use(express.static(path.join(__dirname, 'public')));

// Tracker page
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// Viewer page
app.get('/track/:sessionId', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'viewer.html'));
});

// API: Get session data
app.get('/api/session/:sessionId', (req, res) => {
  const session = sessions.get(req.params.sessionId);
  if (!session) {
    return res.status(404).json({ error: 'Session not found' });
  }
  res.json({
    sessionId: session.id,
    isActive: session.isActive,
    locations: session.locations,
    startedAt: session.startedAt,
    lastUpdate: session.lastUpdate
  });
});

app.use(express.json());

// API: Receive location update from HTTP Tracker
app.post('/api/location', (req, res) => {
  const { sessionId, latitude, longitude, accuracy, speed, heading, altitude, timestamp } = req.body;
  if (!sessionId || latitude == null || longitude == null) {
    return res.status(400).json({ error: 'Missing required fields' });
  }

  let session = sessions.get(sessionId);
  if (!session) {
    session = {
      id: sessionId,
      trackerId: 'http-tracker',
      isActive: true,
      locations: [],
      startedAt: Date.now(),
      lastUpdate: Date.now(),
      viewers: new Set()
    };
    sessions.set(sessionId, session);
    console.log(`Created new HTTP session: ${sessionId}`);
  }

  session.isActive = true;

  const locationPoint = {
    latitude,
    longitude,
    accuracy,
    speed,
    heading,
    altitude,
    timestamp: timestamp || Date.now()
  };

  session.locations.push(locationPoint);
  session.lastUpdate = Date.now();

  if (session.locations.length > 1000) {
    session.locations = session.locations.slice(-1000);
  }

  // Broadcast to socket viewers
  io.to(`session-${sessionId}`).emit('location-update', locationPoint);

  res.json({ success: true, viewers: session.viewers.size });
});

// Socket.io connection handling
io.on('connection', (socket) => {
  console.log(`Client connected: ${socket.id}`);

  // Tracker starts a new session
  socket.on('start-tracking', () => {
    const sessionId = uuidv4().split('-')[0]; // Short ID
    const session = {
      id: sessionId,
      trackerId: socket.id,
      isActive: true,
      locations: [],
      startedAt: Date.now(),
      lastUpdate: Date.now(),
      viewers: new Set()
    };
    sessions.set(sessionId, session);
    socket.join(`session-${sessionId}`);
    socket.sessionId = sessionId;
    socket.isTracker = true;

    socket.emit('session-created', {
      sessionId,
      shareLink: `/track/${sessionId}`
    });
    console.log(`Tracking session created: ${sessionId}`);
  });

  // Tracker re-joins after reconnect (server restart / network drop)
  socket.on('rejoin-tracking', (data) => {
    const sessionId = data.sessionId;
    let session = sessions.get(sessionId);
    
    if (!session) {
      // Server was restarted — recreate the session
      session = {
        id: sessionId,
        trackerId: socket.id,
        isActive: true,
        locations: [],
        startedAt: Date.now(),
        lastUpdate: Date.now(),
        viewers: new Set()
      };
      sessions.set(sessionId, session);
      console.log(`Session ${sessionId} re-created after server restart`);
    } else {
      // Session exists — just update the tracker socket ID
      session.trackerId = socket.id;
      session.isActive = true;
    }

    socket.join(`session-${sessionId}`);
    socket.sessionId = sessionId;
    socket.isTracker = true;

    socket.emit('rejoin-confirmed', { sessionId });
    console.log(`Tracker rejoined session: ${sessionId}`);
  });

  // Tracker sends location update
  socket.on('location-update', (data) => {
    const session = sessions.get(data.sessionId);
    if (!session || session.trackerId !== socket.id) return;

    const locationPoint = {
      latitude: data.latitude,
      longitude: data.longitude,
      accuracy: data.accuracy,
      speed: data.speed,
      heading: data.heading,
      altitude: data.altitude,
      timestamp: data.timestamp || Date.now()
    };

    session.locations.push(locationPoint);
    session.lastUpdate = Date.now();

    // Keep only last 1000 points to prevent memory issues
    if (session.locations.length > 1000) {
      session.locations = session.locations.slice(-1000);
    }

    // Broadcast to all viewers in this session
    socket.to(`session-${data.sessionId}`).emit('location-update', locationPoint);
  });

  // Viewer joins a session
  socket.on('join-session', (data) => {
    let session = sessions.get(data.sessionId);
    if (!session) {
      // Session doesn't exist yet (tracker hasn't got GPS lock). 
      // Create a pending session so the viewer can wait.
      session = {
        id: data.sessionId,
        trackerId: 'pending',
        isActive: true,
        locations: [],
        startedAt: Date.now(),
        lastUpdate: Date.now(),
        viewers: new Set()
      };
      sessions.set(data.sessionId, session);
    }

    socket.join(`session-${data.sessionId}`);
    socket.sessionId = data.sessionId;
    session.viewers.add(socket.id);

    // Send existing session data
    socket.emit('session-data', {
      sessionId: session.id,
      isActive: session.isActive,
      locations: session.locations,
      startedAt: session.startedAt,
      lastUpdate: session.lastUpdate,
      viewerCount: session.viewers.size
    });

    // Notify tracker about viewer count
    io.to(session.trackerId).emit('viewer-count', {
      count: session.viewers.size
    });

    console.log(`Viewer joined session ${data.sessionId}. Viewers: ${session.viewers.size}`);
  });

  // Tracker stops tracking
  socket.on('stop-tracking', (data) => {
    const session = sessions.get(data.sessionId);
    if (!session || session.trackerId !== socket.id) return;

    session.isActive = false;
    io.to(`session-${data.sessionId}`).emit('tracking-stopped', {
      sessionId: data.sessionId,
      totalLocations: session.locations.length
    });
    console.log(`Tracking stopped: ${data.sessionId}`);
  });

  // Handle disconnect
  socket.on('disconnect', () => {
    console.log(`Client disconnected: ${socket.id}`);

    if (socket.isTracker && socket.sessionId) {
      const session = sessions.get(socket.sessionId);
      if (session) {
        session.isActive = false;
        io.to(`session-${socket.sessionId}`).emit('tracking-stopped', {
          sessionId: socket.sessionId,
          reason: 'Tracker disconnected'
        });
      }
    } else if (socket.sessionId) {
      const session = sessions.get(socket.sessionId);
      if (session) {
        session.viewers.delete(socket.id);
        io.to(session.trackerId).emit('viewer-count', {
          count: session.viewers.size
        });
      }
    }
  });
});

// Cleanup old sessions every hour
setInterval(() => {
  const now = Date.now();
  const MAX_AGE = 24 * 60 * 60 * 1000; // 24 hours
  for (const [id, session] of sessions) {
    if (now - session.lastUpdate > MAX_AGE) {
      sessions.delete(id);
      console.log(`Cleaned up expired session: ${id}`);
    }
  }
}, 60 * 60 * 1000);

server.listen(PORT, () => {
  console.log(`\n🛰️  GPS Tracker Server Running!`);
  console.log(`📍 Open http://localhost:${PORT} to start tracking`);
  console.log(`🌐 Share your tracking link with anyone to let them follow you\n`);
});
