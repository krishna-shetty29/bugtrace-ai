import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'

async function start() {
  const root = createRoot(document.getElementById('root')!)
  if (import.meta.env.MODE === 'public-demo') {
    const { default: PublicDemo } = await import('./PublicDemo')
    root.render(<StrictMode><PublicDemo /></StrictMode>)
  } else {
    const [{ default: App }, { default: TeamAccessGate }, { installTeamFetch }] = await Promise.all([
      import('./App'), import('./TeamAccessGate'), import('./teamClient'),
    ])
    installTeamFetch()
    root.render(<StrictMode><TeamAccessGate><App /></TeamAccessGate></StrictMode>)
  }
}
void start()
