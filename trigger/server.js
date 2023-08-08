import express from 'express'
import bodyParser from 'body-parser'
import cors from 'cors'
import { Bonjour } from 'bonjour-service'

import { saveJson } from './lib/utils.js'

export const initServer = ({ configFile, scheduler, controller, logger }) => {
  const app = express()

  const bonjour = new Bonjour({}, (err) => {
    logger.log('server', 'Bonjour failed to init', err)
  })

  app.use(bodyParser.json())
  app.use(cors())

  app.get('/', (req, res) => {
    return res.status(200).json({
      timestamp: new Date(),
      status: 'OK',
      jobs: scheduler.jobs,
      recording: scheduler.cam.recording,
      config: scheduler.config,
    })
  })

  app.get('/logs', (req, res) => {
    return res.status(200).json({
      timestamp: new Date(),
      logs: logger.logMessages,
    })
  })

  app.post('/test', (req, res) => {
    controller
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

  app.post('/save', async (req, res) => {
    try {
      if (!req.body.logFile || !req.body.cameraType) {
        return res.status(400).json({ success: false })
      }

      await saveJson(configFile, req.body)

      scheduler.teardown()
      scheduler.initialize(req.body)

      return res.status(200).json({
        timestamp: new Date(),
        status: 'OK',
        jobs: scheduler.jobs,
        recording: scheduler.cam.recording,
        config: scheduler.config,
      })
    } catch (err) {
      return res
        .status(500)
        .json({ success: false, error: err?.message ?? err })
    }
  })

  const port = process.env.NODE_PORT || 3000
  const server = app.listen(port, () => {
    const service = bonjour.publish({
      name: scheduler.config.deviceName,
      type: 'http',
      protocol: 'tcp',
      txt: {
        _scheduled_camera_trigger: 'com.noahprail.camscheduler',
        _scheduled_camera_trigger_id: scheduler.config.deviceId,
        _scheduled_camera_trigger_type: scheduler.config.cameraType,
      },
      port,
    })

    service.start()

    logger.log('server', 'started', { port })
  })

  return { server, bonjour }
}
