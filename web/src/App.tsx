import { useState } from 'react'
import { InboxView } from './components/InboxView'
import { ShoppingView } from './components/ShoppingView'
import { NotesView } from './components/NotesView'

type Tab = 'inbox' | 'shopping' | 'notes'

const TABS: { id: Tab; label: string }[] = [
  { id: 'inbox', label: 'Inbox' },
  { id: 'shopping', label: 'Einkaufsliste' },
  { id: 'notes', label: 'Notizen' },
]

export default function App() {
  const [tab, setTab] = useState<Tab>('inbox')

  return (
    <div className="pb-16">
      {tab === 'inbox' && <InboxView />}
      {tab === 'shopping' && <ShoppingView />}
      {tab === 'notes' && <NotesView />}

      <nav className="fixed bottom-0 inset-x-0 bg-white border-t border-gray-200 flex z-40">
        {TABS.map(({ id, label }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`flex-1 py-3 text-sm font-medium transition ${
              tab === id ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'
            }`}
          >
            {label}
          </button>
        ))}
      </nav>
    </div>
  )
}
