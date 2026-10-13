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

Variáveis opcionais: `PORT` (padrão 8080) e `THREADS` (requisições processadas em paralelo; padrão = nº de CPUs).

## Endpoints

| Método | Rota       | Descrição                                                        |
|--------|------------|------------------------------------------------------------------|
| GET    | `/health`  | Verifica se a aplicação está no ar                                |
| GET    | `/restore` | Restaura a imagem de exemplo `images/lena-borrada.jpg` (usar nos testes de carga) |
| POST   | `/restore` | Restaura a imagem enviada no corpo (até 10 MB)                    |

As respostas de `/restore` são PNG e trazem o cabeçalho `X-Processing-Time-Ms`.

```bash
curl http://localhost:8080/health
curl -o restaurada.png http://localhost:8080/restore
curl -o restaurada.png --data-binary @images/lena-borrada.jpg http://localhost:8080/restore
```
