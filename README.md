# RedeCanais para CloudStream

Extensão de filmes, séries, animes e novelas do RedeCanais, com **domínio configurável**
(sem domínio fixo no repositório) e resolução de desafio Cloudflare via WebView.

## Instalação

1. CloudStream → Configurações → Extensões → Adicionar repositório
2. Cole: `https://raw.githubusercontent.com/SEU_USUARIO/redecanais-cloudstream/builds/repo.json`
3. Instale "RedeCanais" e "RedeCanais TV"

## Primeiro uso (obrigatório)

A extensão não vem com domínio embutido. Na primeira busca, digite a URL atual do site
no campo de pesquisa (ex.: `redecanais.by`) e busque — a extensão valida, salva e passa a
funcionar. Para trocar depois: Configurações da extensão, ou busque a nova URL novamente.

Se a busca abrir o desafio do Cloudflare, aguarde a verificação automática (alguns segundos).

## Build local

    ./gradlew make

Gera os `.cs3` em `build/builds/`.

## Limitações

- `cf_clearance` é vinculado a IP + User-Agent; trocar de rede exige nova verificação.
- Domínios do RedeCanais mudam com frequência — atualize o endereço quando falhar.