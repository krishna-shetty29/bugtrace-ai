import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import TeamAccessGate from './TeamAccessGate'
import { installTeamFetch } from './teamClient'

installTeamFetch()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <TeamAccessGate><App /></TeamAccessGate>
  </StrictMode>,
)
