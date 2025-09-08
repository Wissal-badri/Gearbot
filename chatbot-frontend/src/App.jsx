import { useState, useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import SendIcon from './assets/send.webp'
import Gear9Logo from './assets/gear9-logo.webp'

const API_BASE = (typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.VITE_API_URL) ? import.meta.env.VITE_API_URL : 'http://localhost:8080'
const BACKEND_URL = `${API_BASE.replace(/\/$/, '')}/api/chat`

export default function App() {
  const [isChatOpen, setIsChatOpen] = useState(false)
  const [isFull, setIsFull] = useState(false)
  const [messages, setMessages] = useState([
    { sender: 'bot', text: `Bonjour ! J'espère que vous allez bien.

Je suis le chatbot de **Gear9** et je suis à votre disposition pour répondre à vos questions sur l'entreprise (adresse, services, projets, clients, distinctions, expertises, etc.).` }
  ])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [conversationId] = useState(() => generateConversationId())
  const inputRef = useRef(null)
  const messagesRef = useRef(null)
  const endRef = useRef(null)

  // Generate a unique conversation ID
  function generateConversationId() {
    return 'conv_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
  }

  // Format bot replies for better readability
  function formatBotText(text) {
    if (!text) return ''
    // If backend returned pipe-separated items, turn them into a bullet list
    if (text.includes(' | ') && !text.includes('\n- ')) {
      const parts = text.split(' | ').map(p => p.trim()).filter(Boolean)
      if (parts.length > 1) {
        return `- ${parts.join('\n- ')}`
      }
    }
    // Ensure the segment before ':' in list items is bold (e.g., "- Title: description")
    const lines = text.split('\n').map(line => {
      const trimmed = line.trimStart()
      if (trimmed.startsWith('-')) {
        const idx = line.indexOf(':')
        if (idx > -1) {
          const before = line.slice(0, idx)
          // Skip if already bolded
          if (!before.includes('**')) {
            const after = line.slice(idx + 1).trimStart()
            return before.replace(/^(\s*-\s*)(.*)$/, (_, bullet, title) => `${bullet}**${title.trim()}**: ${after}`)
          }
        }
      }
      return line
    })
    const joined = lines.join('\n')
    return emphasizeBrand(joined)
  }

  // Bold every occurrence of Gear9 (case-insensitive) unless already bolded
  function emphasizeBrand(text) {
    if (!text) return ''
    const re = /\bgear9\b/gi
    return text.replace(re, (match, offset, s) => {
      const before = s.slice(Math.max(0, offset - 2), offset)
      const after = s.slice(offset + match.length, offset + match.length + 2)
      if (before === '**' && after === '**') {
        return match
      }
      return `**${match}**`
    })
  }

  function normalizeFormatting(text) {
    if (!text) return text
    // Convert pipe-separated lists to Markdown bullets if present
    if (text.includes(' | ') && !text.includes('\n- ')) {
      const parts = text.split(' | ').map(s => s.trim()).filter(Boolean)
      if (parts.length > 1) {
        return '- ' + parts.join('\n- ')
      }
    }
    return text
  }

  // Focus input automatically when chat opens
  useEffect(() => {
    if (isChatOpen) {
      setTimeout(() => inputRef.current && inputRef.current.focus(), 0)
      // Ensure we see the latest messages when opening
      requestAnimationFrame(() => {
        const el = messagesRef.current
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  }, [isChatOpen])

  // Auto-scroll whenever messages or typing state changes
  useEffect(() => {
    requestAnimationFrame(() => {
      if (endRef.current) {
        endRef.current.scrollIntoView({ behavior: 'smooth', block: 'end' })
      } else if (messagesRef.current) {
        const el = messagesRef.current
        el.scrollTop = el.scrollHeight
      }
    })
  }, [messages, loading])

  async function sendMessage(e) {
    e.preventDefault()
    const trimmed = input.trim()
    if (!trimmed) return

    setMessages(prev => [...prev, { sender: 'user', text: trimmed }])
    setInput('')
    setLoading(true)
    setError('')

    try {
      const res = await fetch(BACKEND_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          message: trimmed,
          conversationId: conversationId
        })
      })
      if (!res.ok) throw new Error('Server error')
      const data = await res.json()
      const reply = data.reply ?? 'No reply'
      setMessages(prev => [...prev, { sender: 'bot', text: normalizeFormatting(reply) }])
    } catch (err) {
      setError('Failed to get response from server.')
      setMessages(prev => [...prev, { sender: 'bot', text: 'Désolé, j\'ai rencontré un problème.' }])
    } finally {
      setLoading(false)
      // Return focus to input for rapid consecutive messages
      setTimeout(() => inputRef.current && inputRef.current.focus(), 0)
    }
  }

  return (
    <div className="app-shell">
      <header className="header">
        <div className="header-inner">
          <div className="logo"><img className="logo-img" src={Gear9Logo} alt="Gear9 logo" /> GearBot</div>
          <button className="cta" onClick={() => setIsChatOpen(true)}>Ouvrir le chatbot</button>
        </div>
      </header>

      <section className="hero">
        <div className="hero-title">Nous offrons une solide expertise dans le <span className="accent-text">Digital</span></div>
        <div className="hero-sub">Cliquez sur le bouton pour ouvrir l'assistant.</div>
      </section>

      <button
        className="chat-launcher"
        aria-label={isChatOpen ? 'Fermer le chatbot' : 'Ouvrir le chatbot'}
        onClick={() => setIsChatOpen(v => !v)}
        title={isChatOpen ? 'Fermer' : 'Ouvrir'}
      >
        💬
      </button>

      {isChatOpen && (
        <div className={isFull ? "chat-overlay" : "chat-panel"} role="dialog" aria-modal="true" aria-label="Chatbot interne Gear9">
          <div className="chat-header-bar">
            <div className="chat-title">GearBot — Chatbot interne Gear9</div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="chat-action" title={isFull ? 'Réduire' : 'Plein écran'} onClick={() => setIsFull(v => !v)}>{isFull ? '▭' : '⤢'}</button>
              <button className="chat-close" aria-label="Fermer" onClick={() => setIsChatOpen(false)}>×</button>
            </div>
          </div>
          <div className="messages" id="messages" ref={messagesRef} aria-live="polite">
            {messages.map((m, idx) => (
              <div key={idx} className={`bubble ${m.sender === 'user' ? 'user' : 'bot'}`}>
                <span className="meta">{m.sender === 'user' ? 'Vous' : 'GearBot'}</span>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.sender === 'bot' ? formatBotText(m.text) : emphasizeBrand(m.text)}</ReactMarkdown>
              </div>
            ))}
            {loading && (
              <div className="bubble bot">
                <span className="meta">GearBot</span>
                <span className="typing"><span className="dot"></span><span className="dot"></span><span className="dot"></span></span>
              </div>
            )}
            <div ref={endRef} />
          </div>

          <form className="composer" onSubmit={sendMessage}>
            <input
              className="input"
              placeholder="Écrivez votre message..."
              value={input}
              onChange={e => setInput(e.target.value)}
              disabled={loading}
              ref={inputRef}
              autoFocus
            />
            <button className="send" type="submit" disabled={loading} aria-label="Envoyer">
              <img src={SendIcon} width={20} height={20} alt="Envoyer" />
            </button>
          </form>

          {error && <div style={{ color: 'crimson', margin: '8px 12px' }}>{error}</div>}
        </div>
      )}
    </div>
  )
} 