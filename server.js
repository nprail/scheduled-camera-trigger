import http from 'http'

const sendJsonResponse = (res, code, json) => {
  res.statusCode = code
  res.setHeader('Content-Type', 'application/json')
  res.write(JSON.stringify(json))
  res.end()
}

export const initServer = (config, jobs, cam, logger) => {
  const hostname = '0.0.0.0'
  const port = process.env.NODE_PORT || 3000

  const server = http.createServer((req, res) => {
    if (req.url !== '/logs') logger.log('server', `${req.method} ${req.url}`)

    if (req.url === '/logs' && req.method === 'GET') {
      return sendJsonResponse(res, 200, logger.logMessages)
    }

    return sendJsonResponse(res, 200, {
      status: 'OK',
      jobs,
      recording: cam.recording,
      config,
    })
  })

  server.listen(port, hostname, () => {
    logger.log('server', 'started')
  })
}
