import { useState } from 'react'
import { TodosView } from './components/TodosView'
import { ShoppingView } from './components/ShoppingView'
import { NotesView } from './components/NotesView'

type Tab = 'todos' | 'shopping' | 'notes'

const TABS: { id: Tab; label: string }[] = [
  { id: 'todos', label: 'Aufgaben' },
  { id: 'shopping', label: 'Einkaufsliste' },
  { id: 'notes', label: 'Notizen' },
]

export default function App() {
  const [tab, setTab] = useState<Tab>('todos')

  return (
    <div className="pb-16">
      {tab === 'todos' && <TodosView />}
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
