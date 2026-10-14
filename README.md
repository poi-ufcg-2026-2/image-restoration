# Image Restoration — backend CPU-bound

Backend HTTP que restaura imagens borradas usando o algoritmo de **Richardson-Lucy**
(versão serial do [Lab2 de Programação Concorrente](https://github.com/giovannifs/fpc/tree/master/2025.1/Lab2)).

Cada requisição executa convoluções sucessivas em ponto flutuante e praticamente não faz I/O,
o que caracteriza o perfil **CPU-bound**.

Requer apenas o JDK 17+ (`sudo apt install openjdk-21-jdk-headless`). Não usa bibliotecas externas.

## Executar

```bash
./build.sh
./run.sh            # porta 8080
```

Variáveis opcionais: `PORT` (padrão 8080), `THREADS` (requisições processadas em paralelo; padrão = nº de CPUs)
e `METRICS_INTERVAL_S` (intervalo do log de métricas; padrão 5, `0` desativa).

## Endpoint

`POST /restore` — restaura a imagem enviada no corpo (até 10 MB) e devolve um PNG com o cabeçalho
`X-Processing-Time-Ms`. Outros métodos recebem `405`.

```bash
curl -o restaurada.png --data-binary @images/lena-borrada.jpg http://localhost:8080/restore
```

## Logs

Todas as linhas têm timestamp, nível, thread e campos `chave=valor` (decimais sempre com ponto), prontas para
`grep`/`awk` ou para carregar num DataFrame. Há três tipos de linha:

**Por requisição** (thread `worker-N`):

```
INFO  [worker-1] req=3 POST /restore client=127.0.0.1 status=200 img=256x256 pixels=65536 in_bytes=131064 out_bytes=151609 em_andamento=2 fila=4 queue_ms=1222 decode_ms=6 restore_ms=1550 restore_cpu_ms=1549 cpu_ratio=1.00 encode_ms=23 total_ms=1580 cpu_ms=1581 latency_ms=2803 mpixels_por_s=0.042
```

| Campo | Significado |
|-------|-------------|
| `em_andamento`, `fila` | requisições sendo processadas e aguardando no pool quando esta começou |
| `queue_ms` | tempo esperando uma thread livre do pool |
| `decode_ms`, `restore_ms`, `encode_ms` | tempo de relógio de cada etapa |
| `restore_cpu_ms` | tempo de CPU da thread no Richardson-Lucy |
| `cpu_ratio` | `restore_cpu_ms / restore_ms`; perto de 1 = CPU-bound sem disputa, abaixo de 1 = thread esperando por núcleo |
| `total_ms` | tempo dentro do handler (igual ao cabeçalho `X-Processing-Time-Ms`) |
| `cpu_ms` | tempo de CPU da thread em toda a requisição |
| `latency_ms` | `queue_ms + total_ms`, o que o cliente percebe no servidor |
| `mpixels_por_s` | vazão do algoritmo em megapixels por segundo |

**Periódico** (thread `metrics`, a cada `METRICS_INTERVAL_S`, omitido quando ocioso): vazão (`req_por_s`),
latência média/p95/máx do intervalo, fila e CPU médios, threads ativas, uso de CPU do processo e do sistema
(`cpu_processo_pct`, `cpu_sistema_pct`, em % de todos os núcleos), load average, heap e coletas de GC.

**Resumo** (thread `shutdown`, ao encerrar com Ctrl+C ou SIGTERM): totais da execução, vazão média e latências
p50/p95/p99/máx.

Requisições rejeitadas (400, 405, 413) são registradas como `WARN` com o motivo; falhas internas como `ERROR` com stack trace.
