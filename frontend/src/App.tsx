import { useState } from 'react'
import './App.css'

type HealthResponse = {
  status: string
  service: string
}

function App() {
  const [health, setHealth] = useState<HealthResponse | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function checkBackend() {
    setLoading(true)
    setError('')
    setHealth(null)

    try {
      const response = await fetch('/api/health', {
        headers: {
          Accept: 'application/json',
        },
      })

      if (!response.ok) {
        throw new Error(`Backend returned HTTP ${response.status}`)
      }

      const data: HealthResponse = await response.json()

      if (
        typeof data.status !== 'string' ||
        typeof data.service !== 'string'
      ) {
        throw new Error('The backend returned an unexpected response')
      }

      setHealth(data)
    } catch (error) {
      setError(
        error instanceof Error
          ? error.message
          : 'Unable to connect to the backend',
      )
    } finally {
      setLoading(false)
    }
  }

  return (
    <main>
      <h1>BugTrace AI</h1>
      <p>Frontend connection check</p>

      <button onClick={checkBackend} disabled={loading}>
        {loading ? 'Checking…' : 'Check backend'}
      </button>

      <div aria-live="polite">
        {health && (
          <p>
            {health.service}: <strong>{health.status}</strong>
          </p>
        )}

        {error && <p role="alert">{error}</p>}
      </div>
    </main>
  )
}

export default App