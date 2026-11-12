# Loja (Google Play)

`developer/`: o perfil de desenvolvedor no Play Console.

- `novelo-icon-512.png`: ícone (o do app: `ic_novelo` em creme sobre terracota, centrado com margem).
- `novelo-header-4096x2304.jpg`: cabeçalho (linho, o novelo, a ponta solta que vira costura, "Novelo." em Fraunces).
- `icon.html` / `header.html`: de onde saíram, renderizados no Chrome headless
  (`--force-device-scale-factor=2 --window-size=2048,1152` pro cabeçalho; a fonte é `app/src/main/res/font/fraunces.ttf`).

Texto promocional (até 140 caracteres):

- en-US: Novelo is a notebook you talk to. Say what's on your mind and it keeps it as tasks, notes and a diary, so your head can rest.
- pt-BR: Novelo é um caderno que te escuta. Fale o que vem à cabeça e ele guarda em tarefas, notas e diário, pra sua cabeça descansar.
- es-419: Novelo es un cuaderno que te escucha. Di lo que tienes en mente y lo guarda en tareas, notas y diario, para que tu cabeza descanse.

Site do desenvolvedor: https://villamediana.github.io/novelo-site/
