package com.opticaguinto.optica;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Punto de entrada: activa la configuración automática y detecta los componentes de este paquete.
@SpringBootApplication
public class OpticaBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpticaBackendApplication.class, args);
	}

}
