import shell from 'shelljs'
import express from 'express'
import bodyParser from 'body-parser'
import cors from 'cors'

import { saveJson } from './utils.js'

export const initServer = ({ config, configFile, scheduler, logger }) => {
  const app = express()

  app.use(bodyParser.json())
  app.use(cors())

  app.get('/', (req, res) => {
    return res.status(200).json({
      timestamp: new Date(),
      status: 'OK',
      jobs: scheduler.jobs,
      recording: scheduler.cam.recording,
      config,
    })
  })

  app.get('/logs', (req, res) => {
    return res.status(200).json({
      timestamp: new Date(),
      logs: logger.logMessages,
    })
  })

  app.post('/kill-wifi', (req, res) => {
    return shell.exec('rfkill block wifi', (code, stdout, stderr) => {
      const resp = {
        timestamp: new Date(),
        code,
        stderr,
        stdout,
      }
      logger.log('server', `rfkill block wifi: ${stdout || stderr}`, resp)

      return res.status(code == 0 ? 200 : 500).json(resp)
    })
  })

  app.post('/test', (req, res) => {
    scheduler.cam
      .test()
      .then(() => {
        res.status(200).json({
          success: true,
        })
      })
      .catch((err) => {
        res.status(500).json({
          success: false,
          error: err?.message ?? err,
        })
      })
  })

  app.post('/save', async (req, res, next) => {
    try {
      await saveJson(configFile, req.body)

      scheduler.teardown()
      scheduler.initialize(config)

      return res.status(200).json({
        timestamp: new Date(),
        status: 'OK',
        jobs: scheduler.jobs,
        recording: scheduler.cam.recording,
        config,
      })
    } catch (err) {
      next(err)
    }
  })

  const port = process.env.NODE_PORT || 3000
  app.listen(port, () => {
    logger.log('server', 'started')
  })
}
