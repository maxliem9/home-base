import { useState } from 'react'
import { InboxView } from './components/InboxView'
import { ShoppingView } from './components/ShoppingView'

type Tab = 'inbox' | 'shopping'

export default function App() {
  const [tab, setTab] = useState<Tab>('inbox')

  return (
    <div className="pb-16">
      {tab === 'inbox' ? <InboxView /> : <ShoppingView />}

      <nav className="fixed bottom-0 inset-x-0 bg-white border-t border-gray-200 flex z-40">
        <button
          onClick={() => setTab('inbox')}
          className={`flex-1 py-3 text-sm font-medium transition ${
            tab === 'inbox' ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'
          }`}
        >
          Inbox
        </button>
        <button
          onClick={() => setTab('shopping')}
          className={`flex-1 py-3 text-sm font-medium transition ${
            tab === 'shopping' ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'
          }`}
        >
          Einkaufsliste
        </button>
      </nav>
    </div>
  )
}
