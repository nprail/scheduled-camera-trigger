import http from 'http'
import shell from 'shelljs'

const sendJsonResponse = (res, code, json) => {
  res.statusCode = code
  res.setHeader('Content-Type', 'application/json')

  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Headers', '*')

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

    if (req.url === '/kill-wifi' && req.method === 'POST') {
      return shell.exec('rfkill block wifi', (code, stdout, stderr) => {
        const resp = {
          code,
          stderr,
          stdout,
        }
        logger.log('server', `rfkill block wifi: ${stdout || stderr}`, resp)

        return sendJsonResponse(res, code == 0 ? 200 : 500, resp)
      })
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
