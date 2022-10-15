import http from 'http'

export const initServer = (config, jobs, cam, logger) => {
  const hostname = '0.0.0.0'
  const port = process.env.NODE_PORT || 3000

  const server = http.createServer((req, res) => {
    res.statusCode = 200
    res.setHeader('Content-Type', 'application/json')
    res.write(
      JSON.stringify({
        status: 'OK',
        jobs,
        recording: cam.recording,
        attempts: config.attempts,
      })
    )
    res.end()
  })

  server.listen(port, hostname, () => {
    logger.log('Server running')
  })
}
